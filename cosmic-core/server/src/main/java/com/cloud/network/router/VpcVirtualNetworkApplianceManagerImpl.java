package com.cloud.network.router;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.Command.OnError;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UpdateNetworkOverviewCommand;
import com.cloud.agent.api.UpdateVmOverviewCommand;
import com.cloud.agent.api.routing.AggregationControlCommand;
import com.cloud.agent.api.routing.AggregationControlCommand.Action;
import com.cloud.agent.api.to.overviews.NetworkOverviewTO;
import com.cloud.agent.api.to.overviews.VMOverviewTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dao.EntityManager;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.PrivateIpAddress;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class VpcVirtualNetworkApplianceManagerImpl extends VirtualNetworkApplianceManagerImpl implements VpcVirtualNetworkApplianceManager {
    private static final Logger s_logger = LoggerFactory.getLogger(VpcVirtualNetworkApplianceManagerImpl.class);

    @Inject
    private NetworkACLManager _networkACLMgr;
    @Inject
    private PrivateIpDao _privateIpDao;
    @Inject
    private Site2SiteVpnManager _s2sVpnMgr;
    @Inject
    private VpcGatewayDao _vpcGatewayDao;
    @Inject
    private NetworkACLItemDao _networkACLItemDao;
    @Inject
    private EntityManager _entityMgr;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _itMgr.registerGuru(VirtualMachine.Type.DomainRouter, this);
        return super.configure(name, params);
    }

    @Override
    public boolean updateVR(final Vpc vpc, final DomainRouterVO router) {
        Commands commands = new Commands(Command.OnError.Stop);

        final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                router,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                null,
                null
        );
        final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
        commands.addCommand(updateNetworkOverviewCommand);

        _commandSetupHelper.createVRConfigCommands(vpc, router, commands);
        try {
            if (_nwHelper.sendCommandsToRouter(router, commands)) {
                s_logger.debug("Successfully applied source NAT list on the vpc " + router.getHostName());
                return true;
            } else {
                s_logger.warn("Failed to apply source NAT list on vpc " + router.getHostName());
                return false;
            }
        } catch (final Exception ex) {
            s_logger.warn("Failed to send config update to router " + router.getHostName());
            return false;
        }
    }

    @Override
    public boolean finalizeVirtualMachineProfile(final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {
        final DomainRouterVO domainRouterVO = _routerDao.findById(profile.getId());

        final Long vpcId = domainRouterVO.getVpcId();

        if (vpcId != null) {
            if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
                String defaultDns1 = null;
                String defaultDns2 = null;
                // remove public and guest nics as we will plug them later
                final Iterator<NicProfile> it = profile.getNics().iterator();
                while (it.hasNext()) {
                    final NicProfile nic = it.next();
                    final Network network = _networkDao.findById(nic.getNetworkId());
                    if (nic.getTrafficType() == TrafficType.Public || (TrafficType.Guest.equals(network.getTrafficType()) && !GuestType.Sync.equals(network.getGuestType()))) {
                        // save dns information
                        if (nic.getTrafficType() == TrafficType.Public) {
                            defaultDns1 = nic.getIPv4Dns1();
                            defaultDns2 = nic.getIPv4Dns2();
                        }
                        s_logger.debug("Removing nic " + nic + " of type " + nic.getTrafficType() + " from the nics passed on vm start. " + "The nic will be plugged later");
                        it.remove();
                    }
                }

                // add vpc cidr/dns/networkdomain to the boot load args
                final StringBuilder buf = profile.getBootArgsBuilder();
                final Vpc vpc = _entityMgr.findById(Vpc.class, vpcId);
                buf.append(" vpccidr=" + vpc.getCidr() + " domain=" + vpc.getNetworkDomain());

                buf.append(" dns1=").append(defaultDns1);
                if (defaultDns2 != null) {
                    buf.append(" dns2=").append(defaultDns2);
                }
            }
        }

        return super.finalizeVirtualMachineProfile(profile, dest, context);
    }

    protected void finalizeIpAssocForNetwork(final VirtualRouter domainRouterVO, final Provider provider, final Long guestNetworkId,
                                             final List<Ip> ipsToExclude) {
        if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
            final ArrayList<? extends PublicIpAddress> publicIps = getPublicIpsToApply(domainRouterVO, provider, guestNetworkId, IpAddress.State.Releasing);

            if (publicIps != null && !publicIps.isEmpty()) {
                s_logger.debug("Found " + publicIps.size() + " ip(s) to apply as a part of domR " + domainRouterVO + " start.");
                // Re-apply public ip addresses - should come before PF/LB/VPN
                _commandSetupHelper.findIpsToExclude(publicIps, ipsToExclude);
            }
        }
    }

    @Override
    protected void finalizeNetworkRulesForNetwork(final Commands cmds, final DomainRouterVO domainRouterVO, final Provider provider, final Long guestNetworkId) {

        super.finalizeNetworkRulesForNetwork(cmds, domainRouterVO, provider, guestNetworkId);

        if (domainRouterVO.getVpcId() != null) {

            if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.NetworkACL, Provider.VPCVirtualRouter)) {
                    final List<NetworkACLItemVO> networkACLs = _networkACLMgr.listNetworkACLItems(guestNetworkId);
                    if (networkACLs != null && !networkACLs.isEmpty()) {
                        s_logger.debug("Found " + networkACLs.size() + " network ACLs to apply as a part of VPC VR " + domainRouterVO + " start for guest network id=" +
                                guestNetworkId);
                        _commandSetupHelper.createNetworkACLsCommands(networkACLs, domainRouterVO, cmds, guestNetworkId, false);
                    }
                }
            }
        }
    }

    @Override
    public boolean finalizeCommandsOnStart(final Commands cmds, final VirtualMachineProfile profile) {
        final DomainRouterVO domainRouterVO = _routerDao.findById(profile.getId());

        final boolean isVpc = domainRouterVO.getVpcId() != null;
        if (!isVpc) {
            return super.finalizeCommandsOnStart(cmds, profile);
        }

        if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
            final List<Nic> nicsToExclude = new ArrayList<>();
            final List<Ip> ipsToExclude = new ArrayList<>();
            final List<StaticRouteProfile> staticRoutesToExclude = new ArrayList<>();

            // 1) FORM SSH CHECK COMMAND
            final NicProfile controlNic = getControlNic(profile);
            if (controlNic == null) {
                s_logger.error("Control network doesn't exist for the router " + domainRouterVO);
                return false;
            }

            finalizeSshAndVersionAndNetworkUsageOnStart(cmds, profile, domainRouterVO, controlNic);

            // 2) FORM PLUG NIC COMMANDS
            final List<Pair<Nic, Network>> syncNics = new ArrayList<>();
            final List<Pair<Nic, Network>> guestNics = new ArrayList<>();
            final List<Pair<Nic, Network>> publicNics = new ArrayList<>();

            final List<? extends Nic> routerNics = _nicDao.listByVmId(profile.getId());
            for (final Nic routerNic : routerNics) {
                final Network network = _networkModel.getNetwork(routerNic.getNetworkId());
                if (network.getTrafficType() == TrafficType.Guest) {
                    final Pair<Nic, Network> guestNic = new Pair<>(routerNic, network);
                    if (GuestType.Sync.equals(network.getGuestType())) {
                        syncNics.add(guestNic);
                    } else {
                        guestNics.add(guestNic);
                    }
                } else if (network.getTrafficType() == TrafficType.Public) {
                    final Pair<Nic, Network> publicNic = new Pair<>(routerNic, network);
                    publicNics.add(publicNic);
                }
            }

            final List<Command> usageCmds = new ArrayList<>();

            // 3) PREPARE PLUG NIC COMMANDS
            try {
                // add VPC router to sync networks
                for (final Pair<Nic, Network> nicNtwk : syncNics) {
                    final Nic syncNic = nicNtwk.first();
                    // plug sync nic
                    final PlugNicCommand plugNicCmd = new PlugNicCommand(
                            _nwHelper.getNicTO(domainRouterVO, syncNic.getNetworkId(), null),
                            domainRouterVO.getInstanceName(),
                            domainRouterVO.getType()
                    );
                    cmds.addCommand(plugNicCmd);
                }
                // add VPC router to public networks
                final List<PublicIp> sourceNat = new ArrayList<>(1);
                for (final Pair<Nic, Network> nicNtwk : publicNics) {
                    final Nic publicNic = nicNtwk.first();
                    final Network publicNtwk = nicNtwk.second();
                    final IPAddressVO userIp = _ipAddressDao.findByIpAndSourceNetworkId(publicNtwk.getId(), publicNic.getIPv4Address());

                    if (userIp.isSourceNat()) {
                        final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                        sourceNat.add(publicIp);

                        if (domainRouterVO.getPublicIpAddress() == null) {
                            final DomainRouterVO routerVO = _routerDao.findById(domainRouterVO.getId());
                            routerVO.setPublicIpAddress(publicNic.getIPv4Address());
                            routerVO.setPublicNetmask(publicNic.getIPv4Netmask());
                            routerVO.setPublicMacAddress(publicNic.getMacAddress());
                            _routerDao.update(routerVO.getId(), routerVO);
                        }
                    }

                    final PlugNicCommand plugNicCmd = new PlugNicCommand(
                            _nwHelper.getNicTO(domainRouterVO, publicNic.getNetworkId(), publicNic.getBroadcastUri().toString()),
                            domainRouterVO.getInstanceName(),
                            domainRouterVO.getType()
                    );
                    cmds.addCommand(plugNicCmd);

                    final VpcVO vpc = _vpcDao.findById(domainRouterVO.getVpcId());
                    final NetworkUsageCommand netUsageCmd = new NetworkUsageCommand(
                            domainRouterVO.getPrivateIpAddress(),
                            domainRouterVO.getInstanceName(),
                            true,
                            publicNic.getIPv4Address(),
                            vpc.getCidr()
                    );
                    usageCmds.add(netUsageCmd);

                    UserStatisticsVO stats = _userStatsDao.findBy(
                            domainRouterVO.getAccountId(),
                            domainRouterVO.getDataCenterId(),
                            publicNtwk.getId(),
                            publicNic.getIPv4Address(),
                            domainRouterVO.getId(),
                            domainRouterVO.getType().toString()
                    );

                    if (stats == null) {
                        stats = new UserStatisticsVO(
                                domainRouterVO.getAccountId(),
                                domainRouterVO.getDataCenterId(),
                                publicNic.getIPv4Address(),
                                domainRouterVO.getId(),
                                domainRouterVO.getType().toString(),
                                publicNtwk.getId()
                        );
                        _userStatsDao.persist(stats);
                    }

                    _commandSetupHelper.createPublicIpACLsCommands(domainRouterVO, cmds);
                }

                // create ip assoc for source nat
                if (!sourceNat.isEmpty()) {
                    _commandSetupHelper.findIpsToExclude(sourceNat, ipsToExclude);
                }

                // add VPC router to guest networks
                for (final Pair<Nic, Network> nicNtwk : guestNics) {
                    final Nic guestNic = nicNtwk.first();
                    // plug guest nic
                    final PlugNicCommand plugNicCmd = new PlugNicCommand(
                            _nwHelper.getNicTO(domainRouterVO, guestNic.getNetworkId(), null),
                            domainRouterVO.getInstanceName(),
                            domainRouterVO.getType()
                    );
                    cmds.addCommand(plugNicCmd);
                    if (_networkModel.isPrivateGateway(guestNic.getNetworkId())) {
                        // set private network
                        final PrivateIpVO ipVO = _privateIpDao.findByIpAndSourceNetworkId(guestNic.getNetworkId(), guestNic.getIPv4Address());
                        final Long privateGwAclId = _vpcGatewayDao.getNetworkAclIdForPrivateIp(ipVO.getVpcId(), ipVO.getNetworkId(), ipVO.getIpAddress());

                        if (privateGwAclId != null) {
                            // set network acl on private gateway
                            final List<NetworkACLItemVO> networkACLs = _networkACLItemDao.listByACL(privateGwAclId);
                            s_logger.debug("Found " + networkACLs.size() + " network ACLs to apply as a part of VPC VR " + domainRouterVO + " start for private gateway ip = "
                                    + ipVO.getIpAddress());

                            _commandSetupHelper.createNetworkACLsCommands(networkACLs, domainRouterVO, cmds, ipVO.getNetworkId(), true);
                        }
                    }
                }
            } catch (final Exception ex) {
                s_logger.warn("Failed to add router " + domainRouterVO + " to network due to exception ", ex);
                return false;
            }

            // 4) REPROGRAM GUEST NETWORK
            boolean reprogramGuestNtwks = profile.getParameter(Param.ReProgramGuestNetworks) == null || (Boolean) profile.getParameter(Param.ReProgramGuestNetworks);

            final VirtualRouterProvider vrProvider = _vrProviderDao.findById(domainRouterVO.getElementId());
            if (vrProvider == null) {
                throw new CloudRuntimeException("Cannot find related virtual router provider of router: " + domainRouterVO.getHostName());
            }
            final Provider provider = Provider.getProvider(vrProvider.getType().toString());
            if (provider == null) {
                throw new CloudRuntimeException("Cannot find related provider of virtual router provider: " + vrProvider.getType().toString());
            }

            boolean isDhcpSupported = false;

            for (final Pair<Nic, Network> nicNtwk : guestNics) {
                final Nic guestNic = nicNtwk.first();

                final AggregationControlCommand startCmd = new AggregationControlCommand(
                        Action.Start,
                        domainRouterVO.getInstanceName(),
                        controlNic.getIPv4Address(),
                        _routerControlHelper.getRouterIpInNetwork(guestNic.getNetworkId(), domainRouterVO.getId())
                );
                cmds.addCommand(startCmd);

                if (reprogramGuestNtwks) {
                    finalizeIpAssocForNetwork(domainRouterVO, provider, guestNic.getNetworkId(), ipsToExclude);
                    finalizeNetworkRulesForNetwork(cmds, domainRouterVO, provider, guestNic.getNetworkId());
                }

                isDhcpSupported = isDhcpSupported || _networkModel.isProviderSupportServiceInNetwork(guestNic.getNetworkId(), Service.Dhcp, provider);

                final AggregationControlCommand finishCmd = new AggregationControlCommand(
                        Action.Finish,
                        domainRouterVO.getInstanceName(),
                        controlNic.getIPv4Address(),
                        _routerControlHelper.getRouterIpInNetwork(guestNic.getNetworkId(), domainRouterVO.getId())
                );
                cmds.addCommand(finishCmd);
            }

            final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                    domainRouterVO,
                    nicsToExclude,
                    ipsToExclude,
                    staticRoutesToExclude,
                    null,
                    null
            );
            final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(domainRouterVO, networkOverview);
            updateNetworkOverviewCommand.setPlugNics(true);
            cmds.addCommand(updateNetworkOverviewCommand);

            if (isDhcpSupported) {
                final VMOverviewTO vmOverview = _commandSetupHelper.createVmOverviewFromRouter(domainRouterVO);
                final UpdateVmOverviewCommand updateVmOverviewCommand = _commandSetupHelper.createUpdateVmOverviewCommand(domainRouterVO, vmOverview);
                cmds.addCommand(updateVmOverviewCommand);
            }

            // 5) RE-APPLY VR Configuration
            final Vpc vpc = _vpcDao.findById(domainRouterVO.getVpcId());
            _commandSetupHelper.createVRConfigCommands(vpc, domainRouterVO, cmds);

            // Add network usage commands
            cmds.addCommands(usageCmds);
        }

        return true;
    }

    @Override
    public void finalizeStop(final VirtualMachineProfile profile, final Answer answer) {
        super.finalizeStop(profile, answer);
        // Mark VPN connections as Disconnected
        final DomainRouterVO router = _routerDao.findById(profile.getId());
        final Long vpcId = router.getVpcId();
        if (vpcId != null) {
            _s2sVpnMgr.markDisconnectVpnConnByVpc(vpcId);
        }
    }

    @Override
    public boolean postStateTransitionEvent(final StateMachine2.Transition<State, VirtualMachine.Event> transition, final VirtualMachine vo, final boolean status, final Object
            opaque) {
        // Without this VirtualNetworkApplianceManagerImpl.postStateTransitionEvent() gets called twice as part of listeners -
        // once from VpcVirtualNetworkApplianceManagerImpl and once from VirtualNetworkApplianceManagerImpl itself
        return true;
    }

    @Override
    public boolean addVpcRouterToGuestNetwork(final VirtualRouter router, final Network network, final Map<VirtualMachineProfile.Param, Object> params)
            throws ConcurrentOperationException, ResourceUnavailableException {
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }

        // Add router to the Guest network
        boolean result = true;
        try {

            // 1) add nic to the router
            _routerDao.addRouterToGuestNetwork(router, network);

            final NicProfile guestNic = _itMgr.addVmToNetwork(router, network, null);
            // 2) setup guest network
            if (guestNic != null) {
                result = setupVpcGuestNetwork(router, true, guestNic);
            } else {
                s_logger.warn("Failed to add router " + router + " to guest network " + network);
                result = false;
            }
            // 3) apply networking rules
            if (result && params.get(Param.ReProgramGuestNetworks) != null && (Boolean) params.get(Param.ReProgramGuestNetworks)) {
                sendNetworkRulesToRouter(router.getId(), network.getId());
            }
        } catch (final Exception ex) {
            s_logger.warn("Failed to add router " + router + " to network " + network + " due to ", ex);
            result = false;
        } finally {
            if (!result) {
                s_logger.debug("Removing the router " + router + " from network " + network + " as a part of cleanup");
                if (removeVpcRouterFromGuestNetwork(router, network)) {
                    s_logger.debug("Removed the router " + router + " from network " + network + " as a part of cleanup");
                } else {
                    s_logger.warn("Failed to remove the router " + router + " from network " + network + " as a part of cleanup");
                }
            } else {
                s_logger.debug("Succesfully added router " + router + " to guest network " + network);
            }
        }

        return result;
    }

    @Override
    public boolean removeVpcRouterFromGuestNetwork(final VirtualRouter router, final Network network)
            throws ConcurrentOperationException, ResourceUnavailableException {
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }

        boolean result = true;
        try {
            // Check if router is a part of the Guest network
            if (!_networkModel.isVmPartOfNetwork(router.getId(), network.getId())) {
                s_logger.debug("Router " + router + " is not a part of the Guest network " + network);
                return result;
            }

            result = setupVpcGuestNetwork(router, false, _networkModel.getNicProfile(router, network.getId(), null));
            if (!result) {
                s_logger.warn("Failed to destroy guest network config " + network + " on router " + router);
                return false;
            }

            result = result && _itMgr.removeVmFromNetwork(router, network, null);
        } finally {
            if (result) {
                _routerDao.removeRouterFromGuestNetwork(router.getId(), network.getId());
            }
        }

        return result;
    }

    protected boolean setupVpcGuestNetwork(final VirtualRouter router, final boolean add, final NicProfile guestNic)
            throws ConcurrentOperationException, ResourceUnavailableException {

        boolean result = true;
        if (router.getState() == State.Running) {
            final Commands cmds = new Commands(Command.OnError.Stop);

            final List<Nic> nicsToExclude = new ArrayList<>();
            if (!add) {
                final Network network = _networkModel.getNetwork(guestNic.getNetworkId());
                final Nic nic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), router.getId());
                nicsToExclude.add(nic);
            }

            final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                    router,
                    nicsToExclude,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    null
            );
            final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
            cmds.addCommand("networkoverview", updateNetworkOverviewCommand);
            _nwHelper.sendCommandsToRouter(router, cmds);

            final Answer setupAnswer = cmds.getAnswer("networkoverview");
            final String setup = add ? "set" : "destroy";
            if (!(setupAnswer != null && setupAnswer.getResult())) {
                s_logger.warn("Unable to " + setup + " guest network on router " + router);
                result = false;
            }
            return result;
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            s_logger.debug("Router " + router.getInstanceName() + " is in " + router.getState() + ", so not sending setup guest network command to the backend");
            return true;
        } else {
            s_logger.warn("Unable to setup guest network on virtual router " + router + " is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to setup guest network on the backend, virtual router " + router + " is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }
    }

    protected boolean sendNetworkRulesToRouter(final long routerId, final long networkId) throws ResourceUnavailableException {
        final DomainRouterVO router = _routerDao.findById(routerId);
        final Commands cmds = new Commands(OnError.Continue);

        final VirtualRouterProvider vrProvider = _vrProviderDao.findById(router.getElementId());
        if (vrProvider == null) {
            throw new CloudRuntimeException("Cannot find related virtual router provider of router: " + router.getHostName());
        }
        final Provider provider = Network.Provider.getProvider(vrProvider.getType().toString());
        if (provider == null) {
            throw new CloudRuntimeException("Cannot find related provider of virtual router provider: " + vrProvider.getType().toString());
        }

        finalizeNetworkRulesForNetwork(cmds, router, provider, networkId);
        return _nwHelper.sendCommandsToRouter(router, cmds);
    }

    @Override
    public boolean destroyPrivateGateway(final PrivateGateway gateway, final VirtualRouter router) throws ConcurrentOperationException, ResourceUnavailableException {
        boolean result;

        if (!_networkModel.isVmPartOfNetwork(router.getId(), gateway.getNetworkId())) {
            s_logger.debug("Router doesn't have nic for gateway " + gateway + " so no need to removed it");
            return true;
        }

        final Network privateNetwork = _networkModel.getNetwork(gateway.getNetworkId());
        final NicProfile nicProfile = _networkModel.getNicProfile(router, privateNetwork.getId(), null);

        s_logger.debug("Releasing private ip for gateway " + gateway + " from " + router);
        result = setupVpcPrivateNetwork(router, false, nicProfile);
        if (!result) {
            s_logger.warn("Failed to release private ip for gateway " + gateway + " on router " + router);
            return false;
        }

        // revoke network acl on the private gateway.
        if (!_networkACLMgr.revokeACLItemsForPrivateGw(gateway)) {
            s_logger.debug("Failed to delete network acl items on " + gateway + " from router " + router);
            return false;
        }

        s_logger.debug("Removing router " + router + " from private network " + privateNetwork + " as a part of delete private gateway");
        result = _itMgr.removeVmFromNetwork(router, privateNetwork, null);
        s_logger.debug("Private gateawy " + gateway + " is removed from router " + router);
        return result;
    }

    /**
     * @param router
     * @param add
     * @param privateNic
     * @return
     * @throws ResourceUnavailableException
     */
    protected boolean setupVpcPrivateNetwork(final VirtualRouter router, final boolean add, final NicProfile privateNic) throws ResourceUnavailableException {

        if (router.getState() == State.Running) {
            final PrivateIpVO ipVO = _privateIpDao.findByIpAndSourceNetworkId(privateNic.getNetworkId(), privateNic.getIPv4Address());
            final Network network = _networkDao.findById(privateNic.getNetworkId());
            final String netmask = NetUtils.getCidrNetmask(network.getCidr());
            String broadcastUri = "";
            if (network.getBroadcastUri() != null) {
                broadcastUri = network.getBroadcastUri().toString();
            }
            final PrivateIpAddress ip = new PrivateIpAddress(ipVO, broadcastUri, network.getGateway(), netmask, privateNic.getMacAddress());

            final Commands cmds = new Commands(Command.OnError.Stop);

            final List<Ip> ipsToExclude = new ArrayList<>();
            if (!add) {
                ipsToExclude.add(new Ip(ip.getIpAddress()));
            }

            final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                    router,
                    new ArrayList<>(),
                    ipsToExclude,
                    new ArrayList<>(),
                    null,
                    null
            );
            final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
            cmds.addCommand(updateNetworkOverviewCommand);

            try {
                if (_nwHelper.sendCommandsToRouter(router, cmds)) {
                    s_logger.debug("Successfully applied ip association for ip " + ip + " in vpc network " + network);
                    return true;
                } else {
                    s_logger.warn("Failed to associate ip address " + ip + " in vpc network " + network);
                    return false;
                }
            } catch (final Exception ex) {
                s_logger.warn("Failed to send  " + (add ? "add " : "delete ") + " private network " + network + " commands to rotuer ");
                return false;
            }
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            s_logger.debug("Router " + router.getInstanceName() + " is in " + router.getState() + ", so not sending setup private network command to the backend");
        } else {
            s_logger.warn("Unable to setup private gateway, virtual router " + router + " is not in the right state " + router.getState());

            throw new ResourceUnavailableException("Unable to setup Private gateway on the backend," + " virtual router " + router + " is not in the right state",
                    DataCenter.class, router.getDataCenterId());
        }
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean startSite2SiteVpn(final Site2SiteVpnConnection conn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            s_logger.warn("Unable to apply site-to-site VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply site 2 site VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return applySite2SiteVpn(true, router, conn);
    }

    @Override
    public boolean refreshSite2SiteVpn(final Site2SiteVpnConnection conn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            s_logger.warn("Unable to apply site-to-site VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply site-to-site VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        final Commands cmds = new Commands(Command.OnError.Continue);
        final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                router,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                null,
                null
        );
        final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
        cmds.addCommand(updateNetworkOverviewCommand);

        return _nwHelper.sendCommandsToRouter(router, cmds);
    }

    @Override
    public boolean stopSite2SiteVpn(final Site2SiteVpnConnection conn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            s_logger.warn("Unable to apply site-to-site VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply site 2 site VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return applySite2SiteVpn(false, router, conn);
    }

    @Override
    public List<DomainRouterVO> getVpcRouters(final long vpcId) {
        return _routerDao.listByVpcId(vpcId);
    }

    @Override
    public boolean startRemoteAccessVpn(final RemoteAccessVpn vpn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            s_logger.warn("Unable to apply remote access VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply remote access VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        final Commands cmds = new Commands(Command.OnError.Stop);

        final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                router,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                null,
                null
        );
        final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
        cmds.addCommand(updateNetworkOverviewCommand);

        try {
            return _nwHelper.sendCommandsToRouter(router, cmds);
        } catch (final Exception ex) {
            s_logger.warn("Failed to delete remote access VPN: domR " + router + " is not in right state " + router.getState());
            return false;
        }
    }

    @Override
    public boolean stopRemoteAccessVpn(final RemoteAccessVpn vpn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() == State.Running) {
            final Commands cmds = new Commands(Command.OnError.Continue);

            final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                    router,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    vpn,
                    null
            );
            final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
            cmds.addCommand(updateNetworkOverviewCommand);

            try {
                return _nwHelper.sendCommandsToRouter(router, cmds);
            } catch (final Exception ex) {
                return false;
            }
        } else if (router.getState() == State.Stopped) {
            s_logger.debug("Router " + router + " is in Stopped state, not sending deleteRemoteAccessVpn command to it");
        } else {
            s_logger.warn("Failed to delete remote access VPN: domR " + router + " is not in right state " + router.getState());
            throw new ResourceUnavailableException("Failed to delete remote access VPN: domR is not in right state " + router.getState(), DataCenter.class,
                    router.getDataCenterId());
        }

        return true;
    }

    private  boolean applySite2SiteVpn(final boolean isCreate, final VirtualRouter router, final Site2SiteVpnConnection conn) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        final NetworkOverviewTO networkOverview = _commandSetupHelper.createNetworkOverviewFromRouter(
                router,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                null,
                isCreate ? null : conn
        );
        final UpdateNetworkOverviewCommand updateNetworkOverviewCommand = _commandSetupHelper.createUpdateNetworkOverviewCommand(router, networkOverview);
        cmds.addCommand(updateNetworkOverviewCommand);

        return _nwHelper.sendCommandsToRouter(router, cmds);
    }
}
