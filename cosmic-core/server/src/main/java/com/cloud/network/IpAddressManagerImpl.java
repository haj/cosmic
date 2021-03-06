package com.cloud.network;

import com.cloud.acl.SecurityChecker.AccessType;
import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.context.CallContext;
import com.cloud.dao.EntityManager;
import com.cloud.db.model.Zone;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.exception.AccountLimitException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.framework.config.ConfigKey;
import com.cloud.framework.config.Configurable;
import com.cloud.model.enumeration.AllocationState;
import com.cloud.model.enumeration.NetworkType;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.IpDeployingRequester;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.server.ResourceTag;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionUtil;
import com.cloud.utils.exception.InvalidParameterValueException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IpAddressManagerImpl extends ManagerBase implements IpAddressManager, Configurable {
    private static final Logger s_logger = LoggerFactory.getLogger(IpAddressManagerImpl.class);
    @Inject
    private NetworkOrchestrationService _networkMgr = null;
    @Inject
    private EntityManager _entityMgr = null;
    @Inject
    private VlanDao _vlanDao = null;
    @Inject
    private IPAddressDao _ipAddressDao = null;
    @Inject
    private AccountDao _accountDao = null;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private AccountVlanMapDao _accountVlanMapDao;
    @Inject
    private DomainVlanMapDao _domainVlanMapDao;
    @Inject
    private NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    private NetworkDao _networksDao = null;
    @Inject
    private RulesManager _rulesMgr;
    @Inject
    private LoadBalancingRulesManager _lbMgr;
    @Inject
    private RemoteAccessVpnService _vpnMgr;
    @Inject
    private PodVlanMapDao _podVlanMapDao;
    @Inject
    private FirewallManager _firewallMgr;
    @Inject
    private FirewallRulesDao _firewallDao;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private NetworkModel _networkModel;
    @Inject
    private Ipv6AddressManager _ipv6Mgr;
    @Inject
    private VpcDao _vpcDao;
    @Inject
    private ResourceTagDao _resourceTagDao;
    @Inject
    private NicDao _nicDao;

    private SearchBuilder<IPAddressVO> AssignIpAddressSearch;
    private SearchBuilder<IPAddressVO> AssignIpAddressFromPodVlanSearch;
    private Random _rand = new Random(System.currentTimeMillis());

    @Override
    public boolean configure(final String name, final Map<String, Object> params) {
        // populate providers
        final Map<Network.Service, Set<Network.Provider>> defaultSharedNetworkOfferingProviders = new HashMap<>();
        final Set<Network.Provider> defaultProviders = new HashSet<>();

        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultSharedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.UserData, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedNetworkOfferingProviders = defaultSharedNetworkOfferingProviders;
        defaultIsolatedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedSourceNatEnabledNetworkOfferingProviders = new HashMap<>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.SourceNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultVPCOffProviders = new HashMap<>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultVPCOffProviders.put(Service.Dhcp, defaultProviders);
        defaultVPCOffProviders.put(Service.Dns, defaultProviders);
        defaultVPCOffProviders.put(Service.UserData, defaultProviders);
        defaultVPCOffProviders.put(Service.NetworkACL, defaultProviders);
        defaultVPCOffProviders.put(Service.Gateway, defaultProviders);
        defaultVPCOffProviders.put(Service.Lb, defaultProviders);
        defaultVPCOffProviders.put(Service.SourceNat, defaultProviders);
        defaultVPCOffProviders.put(Service.StaticNat, defaultProviders);
        defaultVPCOffProviders.put(Service.PortForwarding, defaultProviders);
        defaultVPCOffProviders.put(Service.Vpn, defaultProviders);

        AssignIpAddressSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressSearch.and("dc", AssignIpAddressSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressSearch.and("allocated", AssignIpAddressSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressSearch.and("vlanId", AssignIpAddressSearch.entity().getVlanId(), Op.IN);
        final SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("type", vlanSearch.entity().getVlanType(), Op.EQ);
        vlanSearch.and("networkId", vlanSearch.entity().getNetworkId(), Op.EQ);
        AssignIpAddressSearch.join("vlan", vlanSearch, vlanSearch.entity().getId(), AssignIpAddressSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressSearch.done();

        AssignIpAddressFromPodVlanSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressFromPodVlanSearch.and("dc", AssignIpAddressFromPodVlanSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.and("allocated", AssignIpAddressFromPodVlanSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressFromPodVlanSearch.and("vlanId", AssignIpAddressFromPodVlanSearch.entity().getVlanId(), Op.IN);

        final SearchBuilder<VlanVO> podVlanSearch = _vlanDao.createSearchBuilder();
        podVlanSearch.and("type", podVlanSearch.entity().getVlanType(), Op.EQ);
        podVlanSearch.and("networkId", podVlanSearch.entity().getNetworkId(), Op.EQ);
        final SearchBuilder<PodVlanMapVO> podVlanMapSB = _podVlanMapDao.createSearchBuilder();
        podVlanMapSB.and("podId", podVlanMapSB.entity().getPodId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.join("podVlanMapSB", podVlanMapSB, podVlanMapSB.entity().getVlanDbId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(),
                JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.join("vlan", podVlanSearch, podVlanSearch.entity().getId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.done();

        s_logger.info("Network Manager is configured.");

        return true;
    }

    private IpAddress allocateIP(final Account ipOwner, final boolean isSystem, final long zoneId) throws ResourceAllocationException, InsufficientAddressCapacityException,
            ConcurrentOperationException {
        final Account caller = CallContext.current().getCallingAccount();
        final long callerUserId = CallContext.current().getCallingUserId();
        // check permissions
        _accountMgr.checkAccess(caller, null, false, ipOwner);

        final DataCenter zone = _entityMgr.findById(DataCenter.class, zoneId);

        return allocateIp(ipOwner, isSystem, caller, callerUserId, zone, null);
    }    // An IP association is required in below cases

    protected boolean cleanupIpResources(final long ipId, final long userId, final Account caller) {
        boolean success = true;

        // Revoke all firewall rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.Firewall + "rules as a part of public IP id=" + ipId + " release...");
            if (!_firewallMgr.revokeFirewallRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the firewall rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all firewall rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        // Revoke all PF/Static nat rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.PortForwarding + "/" + Purpose.StaticNat + " rules as a part of public IP id=" + ipId + " release...");
            if (!_rulesMgr.revokeAllPFAndStaticNatRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        s_logger.debug("Revoking all " + Purpose.LoadBalancing + " rules as a part of public IP id=" + ipId + " release...");
        if (!_lbMgr.removeAllLoadBalanacersForIp(ipId, caller, userId)) {
            s_logger.warn("Unable to revoke all the load balancer rules for ip id=" + ipId + " as a part of ip release");
            success = false;
        }

        // remote access vpn can be enabled only for static nat ip, so this part should never be executed under normal
        // conditions
        // only when ip address failed to be cleaned up as a part of account destroy and was marked as Releasing, this part of
        // the code would be triggered
        s_logger.debug("Cleaning up remote access vpns as a part of public IP id=" + ipId + " release...");
        try {
            _vpnMgr.destroyRemoteAccessVpnForIp(ipId, caller);
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Unable to destroy remote access vpn for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        // Remove the tags corresponding to IP.
        if (success) {
            _resourceTagDao.removeByIdAndType(ipId, ResourceTag.ResourceObjectType.PublicIpAddress);
        }

        return success;
    }    //  1.there is at least one public IP associated with the network on which first rule (PF/static NAT/LB) is being applied.

    //  2.last rule (PF/static NAT/LB) on the public IP has been revoked. So the public IP should not be associated with any provider
    boolean checkIfIpAssocRequired(final Network network, final boolean postApplyRules, final List<PublicIp> publicIps) {

        if (network.getState() == Network.State.Implementing) {
            return true;
        }

        for (final PublicIp ip : publicIps) {
            if (ip.isSourceNat()) {
                continue;
            } else if (ip.isOneToOneNat()) {
                continue;
            } else {
                Long totalCount = null;
                Long revokeCount = null;
                Long activeCount = null;
                Long addCount = null;

                totalCount = _firewallDao.countRulesByIpId(ip.getId());
                if (postApplyRules) {
                    revokeCount = _firewallDao.countRulesByIpIdAndState(ip.getId(), FirewallRule.State.Revoke);
                } else {
                    activeCount = _firewallDao.countRulesByIpIdAndState(ip.getId(), FirewallRule.State.Active);
                    addCount = _firewallDao.countRulesByIpIdAndState(ip.getId(), FirewallRule.State.Add);
                }

                if (totalCount == null || totalCount.longValue() == 0L) {
                    continue;
                }

                if (postApplyRules) {

                    if (revokeCount != null && revokeCount.longValue() == totalCount.longValue()) {
                        s_logger.trace("All rules are in Revoke state, have to dis-assiciate IP from the backend");
                        return true;
                    }
                } else {
                    if (activeCount != null && activeCount > 0) {
                        if (network.getVpcId() != null) {
                            // If there are more than one ip in the vpc tier network with services configured on it,
                            // then in case of restart network with cleanup this needs to be return true, because due
                            // to the cleanup all configuration is gone and needs to be resent.
                            return true;
                        }
                        continue;
                    } else if (addCount != null && addCount.longValue() == totalCount.longValue()) {
                        s_logger.trace("All rules are in Add state, have to assiciate IP with the backend");
                        return true;
                    } else {
                        continue;
                    }
                }
            }
        }

        // there are no IP's corresponding to this network that need to be associated with provider
        return false;
    }

    protected IPAddressVO getExistingSourceNatInNetwork(final long ownerId, final Long networkId) {
        final List<? extends IpAddress> addrs;
        final Network guestNetwork = _networksDao.findById(networkId);
        if (guestNetwork.getGuestType() == GuestType.Shared) {
            // ignore the account id for the shared network
            addrs = _networkModel.listPublicIpsAssignedToGuestNtwk(networkId, true);
        } else {
            addrs = _networkModel.listPublicIpsAssignedToGuestNtwk(ownerId, networkId, true);
        }

        IPAddressVO sourceNatIp = null;
        if (addrs.isEmpty()) {
            return null;
        } else {
            // Account already has ip addresses
            for (final IpAddress addr : addrs) {
                if (addr.isSourceNat()) {
                    sourceNatIp = _ipAddressDao.findById(addr.getId());
                    return sourceNatIp;
                }
            }

            assert (sourceNatIp != null) : "How do we get a bunch of ip addresses but none of them are source nat? " + "account=" + ownerId + "; networkId=" + networkId;
        }

        return sourceNatIp;
    }

    protected boolean isSharedNetworkOfferingWithServices(final long networkOfferingId) {
        final NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if ((networkOffering.getGuestType() == Network.GuestType.Shared)
                && (_networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.SourceNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.StaticNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Firewall)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.PortForwarding) || _networkModel.areServicesSupportedByNetworkOffering(
                networkOfferingId, Service.Lb))) {
            return true;
        }
        return false;
    }

    @Override
    public boolean applyRules(final List<? extends FirewallRule> rules, final FirewallRule.Purpose purpose, final NetworkRuleApplier applier, final boolean continueOnError)
            throws ResourceUnavailableException {
        if (rules == null || rules.size() == 0) {
            s_logger.debug("There are no rules to forward to the network elements");
            return true;
        }

        boolean success = true;
        final Network network = _networksDao.findById(rules.get(0).getNetworkId());
        final FirewallRuleVO.TrafficType trafficType = rules.get(0).getTrafficType();
        final List<PublicIp> publicIps = new ArrayList<>();

        if (!(rules.get(0).getPurpose() == FirewallRule.Purpose.Firewall && trafficType == FirewallRule.TrafficType.Egress)) {
            // get the list of public ip's owned by the network
            final List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
            if (userIps != null && !userIps.isEmpty()) {
                for (final IPAddressVO userIp : userIps) {
                    final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                    publicIps.add(publicIp);
                }
            }
        }
        // rules can not programmed unless IP is associated with network service provider, so run IP assoication for
        // the network so as to ensure IP is associated before applying rules (in add state)
        if (checkIfIpAssocRequired(network, false, publicIps)) {
            applyIpAssociations(network, false, continueOnError, publicIps);
        }

        try {
            applier.applyRules(network, purpose, rules);
        } catch (final ResourceUnavailableException e) {
            if (!continueOnError) {
                throw e;
            }
            s_logger.warn("Problems with applying " + purpose + " rules but pushing on", e);
            success = false;
        }

        // if there are no active rules associated with a public IP, then public IP need not be associated with a provider.
        // This IPAssoc ensures, public IP is dis-associated after last active rule is revoked.
        if (checkIfIpAssocRequired(network, true, publicIps)) {
            applyIpAssociations(network, true, continueOnError, publicIps);
        }

        return success;
    }

    @Override
    public String getConfigComponentName() {
        return IpAddressManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{UseSystemPublicIps};
    }

    @Override
    @DB
    public boolean disassociatePublicIpAddress(final long addrId, final long userId, final Account caller) {

        boolean success = true;
        // Cleanup all ip address resources - PF/LB/Static nat rules
        if (!cleanupIpResources(addrId, userId, caller)) {
            success = false;
            s_logger.warn("Failed to release resources for ip address id=" + addrId);
        }

        final IPAddressVO ip = markIpAsUnavailable(addrId);
        if (ip == null) {
            return true;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing ip id=" + addrId + "; sourceNat = " + ip.isSourceNat());
        }

        if (ip.getAssociatedWithNetworkId() != null) {
            final Network network = _networksDao.findById(ip.getAssociatedWithNetworkId());
            try {
                if (!applyIpAssociations(network, true)) {
                    s_logger.warn("Unable to apply ip address associations for " + network);
                    success = false;
                }
            } catch (final ResourceUnavailableException e) {
                throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
            }
        } else {
            if (ip.getState() == IpAddress.State.Releasing) {
                _ipAddressDao.unassignIpAddress(ip.getId());
            }
        }

        if (success) {
            s_logger.debug("Released a public ip id=" + addrId);
        }

        return success;
    }

    @Override
    public PublicIp assignPublicIpAddress(final long dcId, final Long podId, final Account owner, final VlanType type, final Long networkId, final String requestedIp, final
    boolean isSystem)
            throws InsufficientAddressCapacityException {
        return fetchNewPublicIp(dcId, podId, null, owner, type, networkId, false, true, requestedIp, isSystem, null, null);
    }

    @Override
    public PublicIp assignPublicIpAddressFromVlans(final long dcId, final Long podId, final Account owner, final VlanType type, final List<Long> vlanDbIds, final Long networkId,
                                                   final String requestedIp, final boolean isSystem)
            throws InsufficientAddressCapacityException {
        return fetchNewPublicIp(dcId, podId, vlanDbIds, owner, type, networkId, false, true, requestedIp, isSystem, null, null);
    }

    @DB
    public PublicIp fetchNewPublicIp(final long dcId, final Long podId, final List<Long> vlanDbIds, final Account owner, final VlanType vlanUse, final Long guestNetworkId,
                                     final boolean sourceNat, final boolean assign, final String requestedIp, final boolean isSystem, final Long vpcId, final Boolean displayIp)
            throws InsufficientAddressCapacityException {
        final IPAddressVO addr = Transaction.execute(new TransactionCallbackWithException<IPAddressVO, InsufficientAddressCapacityException>() {
            @Override
            public IPAddressVO doInTransaction(final TransactionStatus status) throws InsufficientAddressCapacityException {
                final StringBuilder errorMessage = new StringBuilder("Unable to get ip adress in ");
                boolean fetchFromDedicatedRange = false;
                final List<Long> dedicatedVlanDbIds = new ArrayList<>();
                final List<Long> nonDedicatedVlanDbIds = new ArrayList<>();

                SearchCriteria<IPAddressVO> sc = null;
                if (podId != null) {
                    sc = AssignIpAddressFromPodVlanSearch.create();
                    sc.setJoinParameters("podVlanMapSB", "podId", podId);
                    errorMessage.append(" pod id=" + podId);
                } else {
                    sc = AssignIpAddressSearch.create();
                    errorMessage.append(" zone id=" + dcId);
                }

                // If owner has dedicated Public IP ranges, fetch IP from the dedicated range
                // Otherwise fetch IP from the system pool
                final List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByAccount(owner.getId());
                for (final AccountVlanMapVO map : maps) {
                    if (vlanDbIds == null || vlanDbIds.contains(map.getVlanDbId())) {
                        dedicatedVlanDbIds.add(map.getVlanDbId());
                    }
                }
                final List<DomainVlanMapVO> domainMaps = _domainVlanMapDao.listDomainVlanMapsByDomain(owner.getDomainId());
                for (final DomainVlanMapVO map : domainMaps) {
                    if (vlanDbIds == null || vlanDbIds.contains(map.getVlanDbId())) {
                        dedicatedVlanDbIds.add(map.getVlanDbId());
                    }
                }
                final List<VlanVO> nonDedicatedVlans = _vlanDao.listZoneWideNonDedicatedVlans(dcId);
                for (final VlanVO nonDedicatedVlan : nonDedicatedVlans) {
                    if (vlanDbIds == null || vlanDbIds.contains(nonDedicatedVlan.getId())) {
                        nonDedicatedVlanDbIds.add(nonDedicatedVlan.getId());
                    }
                }
                if (dedicatedVlanDbIds != null && !dedicatedVlanDbIds.isEmpty()) {
                    fetchFromDedicatedRange = true;
                    sc.setParameters("vlanId", dedicatedVlanDbIds.toArray());
                    errorMessage.append(", vlanId id=" + Arrays.toString(dedicatedVlanDbIds.toArray()));
                } else if (nonDedicatedVlanDbIds != null && !nonDedicatedVlanDbIds.isEmpty()) {
                    sc.setParameters("vlanId", nonDedicatedVlanDbIds.toArray());
                    errorMessage.append(", vlanId id=" + Arrays.toString(nonDedicatedVlanDbIds.toArray()));
                } else {
                    if (podId != null) {
                        final InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException("Insufficient address capacity", Pod.class, podId);
                        ex.addProxyObject(ApiDBUtils.findPodById(podId).getUuid());
                        throw ex;
                    }
                    s_logger.warn(errorMessage.toString());
                    final InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException("Insufficient address capacity", DataCenter.class, dcId);
                    ex.addProxyObject(ApiDBUtils.findZoneById(dcId).getUuid());
                    throw ex;
                }

                sc.setParameters("dc", dcId);

                final DataCenter zone = _entityMgr.findById(DataCenter.class, dcId);

                // for direct network take ip addresses only from the vlans belonging to the network
                if (vlanUse == VlanType.DirectAttached) {
                    sc.setJoinParameters("vlan", "networkId", guestNetworkId);
                    errorMessage.append(", network id=" + guestNetworkId);
                }
                sc.setJoinParameters("vlan", "type", vlanUse);

                if (requestedIp != null) {
                    sc.addAnd("address", SearchCriteria.Op.EQ, requestedIp);
                    errorMessage.append(": requested ip " + requestedIp + " is not available");
                }

                final Filter filter = new Filter(IPAddressVO.class, "vlanId", true, 0l, 1l);

                List<IPAddressVO> addrs = _ipAddressDao.lockRows(sc, filter, true);

                // If all the dedicated IPs of the owner are in use fetch an IP from the system pool
                if (addrs.size() == 0 && fetchFromDedicatedRange) {
                    // Verify if account is allowed to acquire IPs from the system
                    final boolean useSystemIps = UseSystemPublicIps.valueIn(owner.getId());
                    if (useSystemIps && nonDedicatedVlanDbIds != null && !nonDedicatedVlanDbIds.isEmpty()) {
                        fetchFromDedicatedRange = false;
                        sc.setParameters("vlanId", nonDedicatedVlanDbIds.toArray());
                        errorMessage.append(", vlanId id=" + Arrays.toString(nonDedicatedVlanDbIds.toArray()));
                        addrs = _ipAddressDao.lockRows(sc, filter, true);
                    }
                }

                if (addrs.size() == 0) {
                    if (podId != null) {
                        final InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException("Insufficient address capacity", Pod.class, podId);
                        // for now, we hardcode the table names, but we should ideally do a lookup for the tablename from the VO object.
                        ex.addProxyObject(ApiDBUtils.findPodById(podId).getUuid());
                        throw ex;
                    }
                    s_logger.warn(errorMessage.toString());
                    final InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException("Insufficient address capacity", DataCenter.class, dcId);
                    ex.addProxyObject(ApiDBUtils.findZoneById(dcId).getUuid());
                    throw ex;
                }

                assert (addrs.size() == 1) : "Return size is incorrect: " + addrs.size();

                if (!fetchFromDedicatedRange && VlanType.VirtualNetwork.equals(vlanUse)) {
                    // Check that the maximum number of public IPs for the given accountId will not be exceeded
                    try {
                        _resourceLimitMgr.checkResourceLimit(owner, ResourceType.public_ip);
                    } catch (final ResourceAllocationException ex) {
                        s_logger.warn("Failed to allocate resource of type " + ex.getResourceType() + " for account " + owner);
                        throw new AccountLimitException("Maximum number of public IP addresses for account: " + owner.getAccountName() + " has been exceeded.");
                    }
                }

                final IPAddressVO addr = addrs.get(0);
                addr.setSourceNat(sourceNat);
                addr.setAllocatedTime(new Date());
                addr.setAllocatedInDomainId(owner.getDomainId());
                addr.setAllocatedToAccountId(owner.getId());
                addr.setSystem(isSystem);
                addr.setIpACLId(NetworkACL.DEFAULT_ALLOW);
                if (displayIp != null) {
                    addr.setDisplay(displayIp);
                }

                if (assign) {
                    markPublicIpAsAllocated(addr);
                } else {
                    addr.setState(IpAddress.State.Allocating);
                }
                addr.setState(assign ? IpAddress.State.Allocated : IpAddress.State.Allocating);

                if (vlanUse != VlanType.DirectAttached) {
                    addr.setAssociatedWithNetworkId(guestNetworkId);
                    addr.setVpcId(vpcId);
                }

                _ipAddressDao.update(addr.getId(), addr);

                return addr;
            }
        });

        if (vlanUse == VlanType.VirtualNetwork) {
            _firewallMgr.addSystemFirewallRules(addr, owner);
        }

        return PublicIp.createFromAddrAndVlan(addr, _vlanDao.findById(addr.getVlanId()));
    }

    @DB
    @Override
    public void markPublicIpAsAllocated(final IPAddressVO addr) {

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final Account owner = _accountMgr.getAccount(addr.getAllocatedToAccountId());
                synchronized (this) {
                    if (_ipAddressDao.lockRow(addr.getId(), true) != null) {
                        final IPAddressVO userIp = _ipAddressDao.findById(addr.getId());
                        if (userIp.getState() == IpAddress.State.Allocating || addr.getState() == IpAddress.State.Free) {
                            addr.setState(IpAddress.State.Allocated);
                            _ipAddressDao.update(addr.getId(), addr);
                            // Save usage event
                            if (owner.getAccountId() != Account.ACCOUNT_ID_SYSTEM) {
                                if (updateIpResourceCount(addr)) {
                                    _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.public_ip);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    private boolean isIpDedicated(final IPAddressVO addr) {
        final List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByVlan(addr.getVlanId());
        if (maps != null && !maps.isEmpty()) {
            return true;
        }
        return false;
    }

    @Override
    public PublicIp assignSourceNatIpAddressToGuestNetwork(final Account owner, final Network guestNetwork) throws InsufficientAddressCapacityException,
            ConcurrentOperationException {
        assert (guestNetwork.getTrafficType() != null) : "You're asking for a source nat but your network "
                + "can't participate in source nat.  What do you have to say for yourself?";
        final long dcId = guestNetwork.getDataCenterId();

        final IPAddressVO sourceNatIp = getExistingSourceNatInNetwork(owner.getId(), guestNetwork.getId());

        PublicIp ipToReturn = null;
        if (sourceNatIp != null) {
            ipToReturn = PublicIp.createFromAddrAndVlan(sourceNatIp, _vlanDao.findById(sourceNatIp.getVlanId()));
        } else {
            ipToReturn = assignDedicateIpAddress(owner, guestNetwork.getId(), null, dcId, true);
        }

        return ipToReturn;
    }

    @DB
    @Override
    public PublicIp assignDedicateIpAddress(final Account owner, final Long guestNtwkId, final Long vpcId, final long dcId, final boolean isSourceNat)
            throws ConcurrentOperationException, InsufficientAddressCapacityException {

        final long ownerId = owner.getId();

        PublicIp ip = null;
        try {
            ip = Transaction.execute(new TransactionCallbackWithException<PublicIp, InsufficientAddressCapacityException>() {
                @Override
                public PublicIp doInTransaction(final TransactionStatus status) throws InsufficientAddressCapacityException {
                    final Account owner = _accountDao.acquireInLockTable(ownerId);

                    if (owner == null) {
                        // this ownerId comes from owner or type Account. See the class "AccountVO" and the annotations in that class
                        // to get the table name and field name that is queried to fill this ownerid.
                        final ConcurrentOperationException ex = new ConcurrentOperationException("Unable to lock account");
                        throw ex;
                    }
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("lock account " + ownerId + " is acquired");
                    }
                    boolean displayIp = true;
                    if (guestNtwkId != null) {
                        final Network ntwk = _networksDao.findById(guestNtwkId);
                        displayIp = ntwk.getDisplayNetwork();
                    } else if (vpcId != null) {
                        final VpcVO vpc = _vpcDao.findById(vpcId);
                        displayIp = vpc.isDisplay();
                    }

                    final PublicIp ip = fetchNewPublicIp(dcId, null, null, owner, VlanType.VirtualNetwork, guestNtwkId, isSourceNat, false, null, false, vpcId, displayIp);
                    final IPAddressVO publicIp = ip.ip();

                    markPublicIpAsAllocated(publicIp);
                    _ipAddressDao.update(publicIp.getId(), publicIp);

                    return ip;
                }
            });

            return ip;
        } finally {
            if (owner != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Releasing lock account " + ownerId);
                }

                _accountDao.releaseFromLockTable(ownerId);
            }
            if (ip == null) {
                s_logger.error("Unable to get source nat ip address for account " + ownerId);
            }
        }
    }

    @Override
    public boolean applyIpAssociations(final Network network, final boolean continueOnError) throws ResourceUnavailableException {
        final List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        boolean success = true;
        // CloudStack will take a lazy approach to associate an acquired public IP to a network service provider as
        // it will not know what service an acquired IP will be used for. An IP is actually associated with a provider when first
        // rule is applied. Similarly when last rule on the acquired IP is revoked, IP is not associated with any provider
        // but still be associated with the account. At this point just mark IP as allocated or released.
        for (final IPAddressVO addr : userIps) {
            if (addr.getState() == IpAddress.State.Allocating) {
                addr.setAssociatedWithNetworkId(network.getId());
                addr.setIpACLId(NetworkACL.DEFAULT_ALLOW);
                markPublicIpAsAllocated(addr);
            } else if (addr.getState() == IpAddress.State.Releasing) {
                // Cleanup all the resources for ip address if there are any, and only then un-assign ip in the system
                if (cleanupIpResources(addr.getId(), Account.ACCOUNT_ID_SYSTEM, _accountMgr.getSystemAccount())) {
                    _ipAddressDao.unassignIpAddress(addr.getId());
                } else {
                    success = false;
                    s_logger.warn("Failed to release resources for ip address id=" + addr.getId());
                }
            }
        }
        return success;
    }

    // CloudStack will take a lazy approach to associate an acquired public IP to a network service provider as
    // it will not know what a acquired IP will be used for. An IP is actually associated with a provider when first
    // rule is applied. Similarly when last rule on the acquired IP is revoked, IP is not associated with any provider
    // but still be associated with the account. Its up to caller of this function to decide when to invoke IPAssociation
    @Override
    public boolean applyIpAssociations(final Network network, final boolean postApplyRules, final boolean continueOnError, final List<? extends PublicIpAddress> publicIps)
            throws ResourceUnavailableException {
        boolean success = true;

        final Map<PublicIpAddress, Set<Service>> ipToServices = _networkModel.getIpToServices(publicIps, postApplyRules, true);
        final Map<Provider, ArrayList<PublicIpAddress>> providerToIpList = _networkModel.getProviderToIpList(network, ipToServices);

        for (final Provider provider : providerToIpList.keySet()) {
            try {
                final ArrayList<PublicIpAddress> ips = providerToIpList.get(provider);
                if (ips == null || ips.isEmpty()) {
                    continue;
                }
                IpDeployer deployer = null;
                final NetworkElement element = _networkModel.getElementImplementingProvider(provider.getName());
                if (!(element instanceof IpDeployingRequester)) {
                    throw new CloudRuntimeException("Element " + element + " is not a IpDeployingRequester!");
                }
                deployer = ((IpDeployingRequester) element).getIpDeployer(network);
                if (deployer == null) {
                    throw new CloudRuntimeException("Fail to get ip deployer for element: " + element);
                }
                final Set<Service> services = new HashSet<>();
                for (final PublicIpAddress ip : ips) {
                    if (!ipToServices.containsKey(ip)) {
                        continue;
                    }
                    services.addAll(ipToServices.get(ip));
                }
                deployer.applyIps(network, ips, services);
            } catch (final ResourceUnavailableException e) {
                success = false;
                if (!continueOnError) {
                    throw e;
                } else {
                    s_logger.debug("Resource is not available: " + provider.getName(), e);
                }
            }
        }

        return success;
    }

    @DB
    @Override
    public IpAddress allocateIp(final Account ipOwner, final boolean isSystem, final Account caller, final long callerUserId, final DataCenter zone, final Boolean displayIp)
            throws ConcurrentOperationException,
            ResourceAllocationException, InsufficientAddressCapacityException {

        final VlanType vlanType = VlanType.VirtualNetwork;
        final boolean assign = false;

        if (AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getId())) {
            // zone is of type DataCenter. See DataCenterVO.java.
            final PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, " + "Zone is currently disabled");
            ex.addProxyObject(zone.getUuid(), "zoneId");
            throw ex;
        }

        PublicIp ip = null;

        Account accountToLock = null;
        try {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address called by the user " + callerUserId + " account " + ipOwner.getId());
            }
            accountToLock = _accountDao.acquireInLockTable(ipOwner.getId());
            if (accountToLock == null) {
                s_logger.warn("Unable to lock account: " + ipOwner.getId());
                throw new ConcurrentOperationException("Unable to acquire account lock");
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address lock acquired");
            }

            ip = Transaction.execute(new TransactionCallbackWithException<PublicIp, InsufficientAddressCapacityException>() {
                @Override
                public PublicIp doInTransaction(final TransactionStatus status) throws InsufficientAddressCapacityException {
                    final PublicIp ip = fetchNewPublicIp(zone.getId(), null, null, ipOwner, vlanType, null, false, assign, null, isSystem, null, displayIp);

                    if (ip == null) {
                        final InsufficientAddressCapacityException ex = new InsufficientAddressCapacityException("Unable to find available public IP addresses", DataCenter
                                .class, zone
                                .getId());
                        ex.addProxyObject(ApiDBUtils.findZoneById(zone.getId()).getUuid());
                        throw ex;
                    }
                    CallContext.current().setEventDetails("Ip Id: " + ip.getId());
                    final Ip ipAddress = ip.getAddress();

                    s_logger.debug("Got " + ipAddress + " to assign for account " + ipOwner.getId() + " in zone " + zone.getId());

                    return ip;
                }
            });
        } finally {
            if (accountToLock != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Releasing lock account " + ipOwner);
                }
                _accountDao.releaseFromLockTable(ipOwner.getId());
                s_logger.debug("Associate IP address lock released");
            }
        }
        return ip;
    }

    @DB
    @Override
    public IPAddressVO associateIPToGuestNetwork(final long ipId, final long networkId, final boolean releaseOnFailure) throws ResourceAllocationException,
            ResourceUnavailableException,
            InsufficientAddressCapacityException, ConcurrentOperationException {
        final Account caller = CallContext.current().getCallingAccount();
        Account owner = null;

        final IPAddressVO ipToAssoc = _ipAddressDao.findById(ipId);
        if (ipToAssoc != null) {
            final Network network = _networksDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Invalid network id is given");
            }

            final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
            if (zone.getNetworkType() == NetworkType.Advanced) {
                if (network.getGuestType() == Network.GuestType.Shared) {
                    if (isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
                        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), AccessType.UseEntry, false,
                                network);
                    } else {
                        throw new InvalidParameterValueException("IP can be associated with guest network of 'shared' type only if "
                                + "network services Source Nat, Static Nat, Port Forwarding, Load balancing, firewall are enabled in the network");
                    }
                }
            } else {
                _accountMgr.checkAccess(caller, null, true, ipToAssoc);
            }
            owner = _accountMgr.getAccount(ipToAssoc.getAllocatedToAccountId());
        } else {
            s_logger.debug("Unable to find ip address by id: " + ipId);
            return null;
        }

        if (ipToAssoc.getAssociatedWithNetworkId() != null) {
            s_logger.debug("IP " + ipToAssoc + " is already assocaited with network id" + networkId);
            return ipToAssoc;
        }

        final Network network = _networksDao.findById(networkId);
        if (network != null) {
            _accountMgr.checkAccess(owner, AccessType.UseEntry, false, network);
        } else {
            s_logger.debug("Unable to find ip address by id: " + ipId);
            return null;
        }

        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());

        // allow associating IP addresses to guest network only
        if (network.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("Ip address can be associated to the network with trafficType " + TrafficType.Guest);
        }

        // Check that network belongs to IP owner - skip this check
        //     - if zone is basic zone as there is just one guest network,
        //     - if shared network in Advanced zone
        //     - and it belongs to the system
        if (network.getAccountId() != owner.getId()) {
            if (zone.getNetworkType() != NetworkType.Basic && !(zone.getNetworkType() == NetworkType.Advanced && network.getGuestType() == Network.GuestType.Shared)) {
                throw new InvalidParameterValueException("The owner of the network is not the same as owner of the IP");
            }
        }

        if (zone.getNetworkType() == NetworkType.Advanced) {
            // In Advance zone allow to do IP assoc only for Isolated networks with source nat service enabled
            if (network.getGuestType() == GuestType.Isolated && !(_networkModel.areServicesSupportedInNetwork(network.getId(), Service.SourceNat))) {
                throw new InvalidParameterValueException("In zone of type " + NetworkType.Advanced + " ip address can be associated only to the network of guest type "
                        + GuestType.Isolated + " with the " + Service.SourceNat.getName() + " enabled");
            }

            // In Advance zone allow to do IP assoc only for shared networks with source nat/static nat/lb/pf services enabled
            if (network.getGuestType() == GuestType.Shared && !isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
                throw new InvalidParameterValueException("In zone of type " + NetworkType.Advanced + " ip address can be associated with network of guest type " + GuestType.Shared
                        + "only if at " + "least one of the services " + Service.SourceNat.getName() + "/" + Service.StaticNat.getName() + "/" + Service.Lb.getName() + "/"
                        + Service.PortForwarding.getName() + " is enabled");
            }
        }

        s_logger.debug("Associating ip " + ipToAssoc + " to network " + network);

        final IPAddressVO ip = _ipAddressDao.findById(ipId);
        //update ip address with networkId
        ip.setAssociatedWithNetworkId(networkId);
        _ipAddressDao.update(ipId, ip);

        boolean success = false;
        try {
            success = applyIpAssociations(network, false);
            if (success) {
                s_logger.debug("Successfully associated ip address " + ip.getAddress().addr() + " to network " + network);
            } else {
                s_logger.warn("Failed to associate ip address " + ip.getAddress().addr() + " to network " + network);
            }
            return ip;
        } finally {
            if (!success && releaseOnFailure) {
                if (ip != null) {
                    try {
                        s_logger.warn("Failed to associate ip address, so releasing ip from the database " + ip);
                        _ipAddressDao.markAsUnavailable(ip.getId());
                        if (!applyIpAssociations(network, true)) {
                            // if fail to apply ip assciations again, unassign ip address without updating resource
                            // count and generating usage event as there is no need to keep it in the db
                            _ipAddressDao.unassignIpAddress(ip.getId());
                        }
                    } catch (final Exception e) {
                        s_logger.warn("Unable to disassociate ip address for recovery", e);
                    }
                }
            }
        }
    }

    protected List<? extends Network> getIsolatedNetworksWithSourceNATOwnedByAccountInZone(final long zoneId, final Account owner) {

        return _networksDao.listSourceNATEnabledNetworks(owner.getId(), zoneId, Network.GuestType.Isolated);
    }

    @Override
    @DB
    public boolean associateIpAddressListToAccount(final long userId, final long accountId, final long zoneId, final Long vlanId, final Network guestNetworkFinal)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException, ResourceAllocationException {
        final Account owner = _accountMgr.getActiveAccountById(accountId);

        if (guestNetworkFinal != null && guestNetworkFinal.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("Network " + guestNetworkFinal + " is not of a type " + TrafficType.Guest);
        }

        try {
            Transaction.execute(new TransactionCallbackWithExceptionNoReturn<Exception>() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientCapacityException,
                        ResourceAllocationException {
                    Network guestNetwork = guestNetworkFinal;

                    if (guestNetwork == null) {
                        final List<? extends Network> networks = getIsolatedNetworksWithSourceNATOwnedByAccountInZone(zoneId, owner);
                        if (networks.size() == 1) {
                            guestNetwork = networks.get(0);
                        } else {
                            throw new InvalidParameterValueException("Error, exactly one guest isolated network with SourceNAT "
                                    + "service enabled is expected for this account, cannot associate the IP range, please provide the network ID");
                        }
                    }

                    // create new Virtual network (Isolated with SourceNAT) for the user if it doesn't exist
                    final List<NetworkOfferingVO> requiredOfferings = _networkOfferingDao.listByAvailability(Availability.Required, false);
                    if (requiredOfferings.size() < 1) {
                        throw new CloudRuntimeException("Unable to find network offering with availability=" + Availability.Required
                                + " to automatically create the network as part of createVlanIpRange");
                    }

                    // Check if there is a source nat ip address for this account; if not - we have to allocate one
                    boolean allocateSourceNat = false;
                    final List<IPAddressVO> sourceNat = _ipAddressDao.listByAssociatedNetwork(guestNetwork.getId(), true);
                    if (sourceNat.isEmpty()) {
                        allocateSourceNat = true;
                    }

                    // update all ips with a network id, mark them as allocated and update resourceCount/usage
                    final List<IPAddressVO> ips = _ipAddressDao.listByVlanId(vlanId);
                    boolean isSourceNatAllocated = false;
                    for (final IPAddressVO addr : ips) {
                        if (addr.getState() != State.Allocated) {
                            if (!isSourceNatAllocated && allocateSourceNat) {
                                addr.setSourceNat(true);
                                isSourceNatAllocated = true;
                            } else {
                                addr.setSourceNat(false);
                            }
                            addr.setAssociatedWithNetworkId(guestNetwork.getId());
                            addr.setVpcId(guestNetwork.getVpcId());
                            addr.setAllocatedTime(new Date());
                            addr.setAllocatedInDomainId(owner.getDomainId());
                            addr.setAllocatedToAccountId(owner.getId());
                            addr.setSystem(false);
                            addr.setState(IpAddress.State.Allocating);
                            markPublicIpAsAllocated(addr);
                        }
                    }
                }
            });
        } catch (final Exception e1) {
            ExceptionUtil.rethrowRuntime(e1);
            ExceptionUtil.rethrow(e1, InsufficientCapacityException.class);
            ExceptionUtil.rethrow(e1, ResourceAllocationException.class);
            throw new IllegalStateException(e1);
        }

        return true;
    }

    @DB
    @Override
    public IPAddressVO markIpAsUnavailable(final long addrId) {
        final IPAddressVO ip = _ipAddressDao.findById(addrId);

        if (ip.getAllocatedToAccountId() == null && ip.getAllocatedTime() == null) {
            s_logger.trace("Ip address id=" + addrId + " is already released");
            return ip;
        }

        if (ip.getState() != State.Releasing) {
            return Transaction.execute(new TransactionCallback<IPAddressVO>() {
                @Override
                public IPAddressVO doInTransaction(final TransactionStatus status) {
                    if (updateIpResourceCount(ip)) {
                        _resourceLimitMgr.decrementResourceCount(_ipAddressDao.findById(addrId).getAllocatedToAccountId(), ResourceType.public_ip);
                    }

                    return _ipAddressDao.markAsUnavailable(addrId);
                }
            });
        }

        return ip;
    }

    protected boolean updateIpResourceCount(final IPAddressVO ip) {
        // don't increment resource count for direct and dedicated ip addresses
        return (ip.getAssociatedWithNetworkId() != null || ip.getVpcId() != null) && !isIpDedicated(ip);
    }

    @Override
    @DB
    public String acquireGuestIpAddress(final Network network, final String requestedIp) {
        if (requestedIp != null && requestedIp.equals(network.getGateway())) {
            s_logger.warn("Requested ip address " + requestedIp + " is used as a gateway address in network " + network);
            return null;
        }

        final SortedSet<Long> availableIps = _networkModel.getAvailableIps(network, requestedIp);

        if (availableIps == null || availableIps.isEmpty()) {
            s_logger.debug("There are no free ips in the  network " + network);
            return null;
        }

        final Long[] array = availableIps.toArray(new Long[availableIps.size()]);

        if (requestedIp != null) {
            // check that requested ip has the same cidr
            final String[] cidr = network.getCidr().split("/");
            final boolean isSameCidr = NetUtils.sameSubnetCIDR(requestedIp, NetUtils.long2Ip(array[0]), Integer.parseInt(cidr[1]));
            if (!isSameCidr) {
                s_logger.warn("Requested ip address " + requestedIp + " doesn't belong to the network " + network + " cidr");
                return null;
            } else if (NetUtils.IsIpEqualToNetworkOrBroadCastIp(requestedIp, cidr[0], Integer.parseInt(cidr[1]))) {
                s_logger.warn("Requested ip address " + requestedIp + " is equal to the to the network/broadcast ip of the network" + network);
                return null;
            }
            return requestedIp;
        }

        return NetUtils.long2Ip(array[_rand.nextInt(array.length)]);
    }

    @Override
    public String acquireGuestIpAddressForVpcRouter(final Vpc vpc, final Network network, final String requestedIp) {
        final List<NicVO> nics = _nicDao.listByNetworkIdAndVmType(network.getId(), VirtualMachine.Type.DomainRouter);
        if (vpc.isRedundant() && !nics.isEmpty()) {
            return nics.get(0).getIPv4Address();
        }

        return acquireGuestIpAddressForRouter(network, requestedIp);
    }

    @Override
    public String acquireGuestIpAddressForRouter(final Network network, final String requestedIp) {

        final SortedSet<Long> availableIps = _networkModel.getAvailableIps(network, requestedIp);

        return (availableIps.isEmpty())
                ? this.acquireGuestIpAddress(network, requestedIp)
                : this.acquireGuestIpAddress(network, NetUtils.long2Ip(availableIps.first()));
    }

    @Override
    public boolean applyStaticNats(final List<? extends StaticNat> staticNats, final boolean continueOnError, final boolean forRevoke) throws ResourceUnavailableException {
        if (staticNats == null || staticNats.size() == 0) {
            s_logger.debug("There are no static nat rules for the network elements");
            return true;
        }

        final Network network = _networksDao.findById(staticNats.get(0).getNetworkId());
        boolean success = true;

        // Check if the StaticNat service is supported
        if (!_networkModel.areServicesSupportedInNetwork(network.getId(), Service.StaticNat)) {
            s_logger.debug("StaticNat service is not supported in specified network id");
            return true;
        }

        // get the list of public ip's owned by the network
        final List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        final List<PublicIp> publicIps = new ArrayList<>();
        if (userIps != null && !userIps.isEmpty()) {
            for (final IPAddressVO userIp : userIps) {
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                publicIps.add(publicIp);
            }
        }

        // static NAT rules can not programmed unless IP is associated with source NAT service provider, so run IP
        // association for the network so as to ensure IP is associated before applying rules
        if (checkStaticNatIPAssocRequired(network, false, forRevoke, publicIps)) {
            applyIpAssociations(network, false, continueOnError, publicIps);
        }

        // get provider
        final StaticNatServiceProvider element = _networkMgr.getStaticNatProviderForNetwork(network);
        try {
            success = element.applyStaticNats(network, staticNats);
        } catch (final ResourceUnavailableException e) {
            if (!continueOnError) {
                throw e;
            }
            s_logger.warn("Problems with " + element.getName() + " but pushing on", e);
            success = false;
        }

        // For revoked static nat IP, set the vm_id to null, indicate it should be revoked
        for (final StaticNat staticNat : staticNats) {
            if (staticNat.isForRevoke()) {
                for (PublicIp publicIp : publicIps) {
                    if (publicIp.getId() == staticNat.getSourceIpAddressId()) {
                        publicIps.remove(publicIp);
                        final IPAddressVO ip = _ipAddressDao.findByIdIncludingRemoved(staticNat.getSourceIpAddressId());
                        // ip can't be null, otherwise something wrong happened
                        ip.setAssociatedWithVmId(null);
                        publicIp = PublicIp.createFromAddrAndVlan(ip, _vlanDao.findById(ip.getVlanId()));
                        publicIps.add(publicIp);
                        break;
                    }
                }
            }
        }

        // if the static NAT rules configured on public IP is revoked then, dis-associate IP with static NAT service provider
        if (checkStaticNatIPAssocRequired(network, true, forRevoke, publicIps)) {
            applyIpAssociations(network, true, continueOnError, publicIps);
        }

        return success;
    }

    // checks if there are any public IP assigned to network, that are marked for one-to-one NAT that
    // needs to be associated/dis-associated with static-nat provider
    boolean checkStaticNatIPAssocRequired(final Network network, final boolean postApplyRules, final boolean forRevoke, final List<PublicIp> publicIps) {
        for (final PublicIp ip : publicIps) {
            if (ip.isOneToOneNat()) {
                Long activeFwCount = null;
                activeFwCount = _firewallDao.countRulesByIpIdAndState(ip.getId(), FirewallRule.State.Active);

                if (!postApplyRules && !forRevoke) {
                    if (activeFwCount > 0) {
                        continue;
                    } else {
                        return true;
                    }
                } else if (postApplyRules && forRevoke) {
                    return true;
                }
            } else {
                continue;
            }
        }
        return false;
    }

    @Override
    public IpAddress assignSystemIp(final long networkId, final Account owner, final boolean forElasticLb, final boolean forElasticIp) throws InsufficientAddressCapacityException {
        final Network guestNetwork = _networksDao.findById(networkId);
        final NetworkOffering off = _entityMgr.findById(NetworkOffering.class, guestNetwork.getNetworkOfferingId());
        IpAddress ip = null;
        if ((off.getElasticLb() && forElasticLb) || (off.getElasticIp() && forElasticIp)) {

            try {
                s_logger.debug("Allocating system IP address for load balancer rule...");
                // allocate ip
                ip = allocateIP(owner, true, guestNetwork.getDataCenterId());
                // apply ip associations
                ip = associateIPToGuestNetwork(ip.getId(), networkId, true);
            } catch (final ResourceAllocationException ex) {
                throw new CloudRuntimeException("Failed to allocate system ip due to ", ex);
            } catch (final ConcurrentOperationException ex) {
                throw new CloudRuntimeException("Failed to allocate system lb ip due to ", ex);
            } catch (final ResourceUnavailableException ex) {
                throw new CloudRuntimeException("Failed to allocate system lb ip due to ", ex);
            }

            if (ip == null) {
                throw new CloudRuntimeException("Failed to allocate system ip");
            }
        }

        return ip;
    }

    @Override
    public boolean handleSystemIpRelease(final IpAddress ip) {
        boolean success = true;
        final Long networkId = ip.getAssociatedWithNetworkId();
        if (networkId != null) {
            if (ip.getSystem()) {
                final CallContext ctx = CallContext.current();
                if (!disassociatePublicIpAddress(ip.getId(), ctx.getCallingUserId(), ctx.getCallingAccount())) {
                    s_logger.warn("Unable to release system ip address id=" + ip.getId());
                    success = false;
                } else {
                    s_logger.warn("Successfully released system ip address id=" + ip.getId());
                }
            }
        }
        return success;
    }

    @Override
    @DB
    public void allocateDirectIp(final NicProfile nic, final Zone zone, final VirtualMachineProfile vm, final Network network, final String requestedIpv4,
                                 final String requestedIpv6) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientAddressCapacityException>() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientAddressCapacityException {
                //This method allocates direct ip for the Shared network in Advance zones
                boolean ipv4 = false;

                if (network.getGateway() != null) {
                    if (nic.getIPv4Address() == null) {
                        ipv4 = true;
                        PublicIp ip = null;

                        //Get ip address from the placeholder and don't allocate a new one
                        if (requestedIpv4 != null && vm.getType() == VirtualMachine.Type.DomainRouter) {
                            final Nic placeholderNic = _networkModel.getPlaceholderNicForRouter(network, null);
                            if (placeholderNic != null) {
                                final IPAddressVO userIp = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), placeholderNic.getIPv4Address());
                                ip = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                                s_logger.debug("Nic got an ip address " + placeholderNic.getIPv4Address() + " stored in placeholder nic for the network " + network);
                            }
                        }

                        if (ip == null) {
                            ip = assignPublicIpAddress(zone.getId(), null, vm.getOwner(), VlanType.DirectAttached, network.getId(), requestedIpv4, false);
                        }

                        nic.setIPv4Address(ip.getAddress().toString());
                        nic.setIPv4Gateway(ip.getGateway());
                        nic.setIPv4Netmask(ip.getNetmask());
                        nic.setIsolationUri(IsolationType.Vlan.toUri(ip.getVlanTag()));
                        //nic.setBroadcastType(BroadcastDomainType.Vlan);
                        //nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(ip.getVlanTag()));
                        nic.setBroadcastType(network.getBroadcastDomainType());
                        if (network.getBroadcastUri() != null) {
                            nic.setBroadcastUri(network.getBroadcastUri());
                        } else {
                            nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(ip.getVlanTag()));
                        }
                        nic.setFormat(AddressFormat.Ip4);
                        nic.setReservationId(String.valueOf(ip.getVlanTag()));
                        nic.setMacAddress(ip.getMacAddress());
                    }
                    nic.setIPv4Dns1(zone.getDns1());
                    nic.setIPv4Dns2(zone.getDns2());
                }

                //FIXME - get ipv6 address from the placeholder if it's stored there
                if (network.getIp6Gateway() != null) {
                    if (nic.getIPv6Address() == null) {
                        final UserIpv6Address ip = _ipv6Mgr.assignDirectIp6Address(zone.getId(), vm.getOwner(), network.getId(), requestedIpv6);
                        final Vlan vlan = _vlanDao.findById(ip.getVlanId());
                        nic.setIPv6Address(ip.getAddress().toString());
                        nic.setIPv6Gateway(vlan.getIp6Gateway());
                        nic.setIPv6Cidr(vlan.getIp6Cidr());
                        if (ipv4) {
                            nic.setFormat(AddressFormat.DualStack);
                        } else {
                            nic.setIsolationUri(IsolationType.Vlan.toUri(vlan.getVlanTag()));
                            nic.setBroadcastType(BroadcastDomainType.Vlan);
                            nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(vlan.getVlanTag()));
                            nic.setFormat(AddressFormat.Ip6);
                            nic.setReservationId(String.valueOf(vlan.getVlanTag()));
                            if (nic.getMacAddress() == null) {
                                nic.setMacAddress(ip.getMacAddress());
                            }
                        }
                    }
                    nic.setIPv6Dns1(zone.getIp6Dns1());
                    nic.setIPv6Dns2(zone.getIp6Dns2());
                }
            }
        });
    }

    @Override
    @DB
    public void allocateNicValues(final NicProfile nic, final Zone zone, final VirtualMachineProfile vm, final Network network, final String requestedIpv4,
                                  final String requestedIpv6) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientAddressCapacityException>() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientAddressCapacityException {
                //This method allocates direct ip for the Shared network in Advance zones
                boolean ipv4 = false;

                if (network.getGateway() != null) {
                    if (nic.getIPv4Address() == null) {
                        ipv4 = true;
                        // PublicIp ip = null;

                        //Get ip address from the placeholder and don't allocate a new one
                        if (requestedIpv4 != null && vm.getType() == VirtualMachine.Type.DomainRouter) {
                            s_logger.debug("There won't be nic assignment for VR id " + vm.getId() + "  in this network " + network);
                        }

                        // nic ip address is not set here. Because the DHCP is external to cloudstack
                        nic.setIPv4Gateway(network.getGateway());
                        nic.setIPv4Netmask(NetUtils.getCidrNetmask(network.getCidr()));

                        final List<VlanVO> vlan = _vlanDao.listVlansByNetworkId(network.getId());

                        //TODO: get vlan tag for the ntwork
                        if (vlan != null && !vlan.isEmpty()) {
                            nic.setIsolationUri(IsolationType.Vlan.toUri(vlan.get(0).getVlanTag()));
                        }

                        nic.setBroadcastType(BroadcastDomainType.Vlan);
                        nic.setBroadcastType(network.getBroadcastDomainType());

                        nic.setBroadcastUri(network.getBroadcastUri());
                        nic.setFormat(AddressFormat.Ip4);

                        if (nic.getMacAddress() == null) {
                            nic.setMacAddress(_networkModel.getNextAvailableMacAddressInNetwork(network.getId()));
                        }
                    }
                    nic.setIPv4Dns1(zone.getDns1());
                    nic.setIPv4Dns2(zone.getDns2());
                }

                // TODO: the IPv6 logic is not changed.
                //FIXME - get ipv6 address from the placeholder if it's stored there
                if (network.getIp6Gateway() != null) {
                    if (nic.getIPv6Address() == null) {
                        final UserIpv6Address ip = _ipv6Mgr.assignDirectIp6Address(zone.getId(), vm.getOwner(), network.getId(), requestedIpv6);
                        final Vlan vlan = _vlanDao.findById(ip.getVlanId());
                        nic.setIPv6Address(ip.getAddress().toString());
                        nic.setIPv6Gateway(vlan.getIp6Gateway());
                        nic.setIPv6Cidr(vlan.getIp6Cidr());
                        if (ipv4) {
                            nic.setFormat(AddressFormat.DualStack);
                        } else {
                            nic.setIsolationUri(IsolationType.Vlan.toUri(vlan.getVlanTag()));
                            nic.setBroadcastType(BroadcastDomainType.Vlan);
                            nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(vlan.getVlanTag()));
                            nic.setFormat(AddressFormat.Ip6);
                            nic.setReservationId(String.valueOf(vlan.getVlanTag()));
                            nic.setMacAddress(ip.getMacAddress());
                        }
                    }
                    nic.setIPv6Dns1(zone.getIp6Dns1());
                    nic.setIPv6Dns2(zone.getIp6Dns2());
                }
            }
        });
    }

    @Override
    public int getRuleCountForIp(final Long addressId, final FirewallRule.Purpose purpose, final FirewallRule.State state) {
        final List<FirewallRuleVO> rules = _firewallDao.listByIpAndPurposeWithState(addressId, purpose, state);
        if (rules == null) {
            return 0;
        }
        return rules.size();
    }

    @Override
    public String allocatePublicIpForGuestNic(final Network network, final Long podId, final Account owner, final String requestedIp) throws InsufficientAddressCapacityException {
        final PublicIp ip = assignPublicIpAddress(network.getDataCenterId(), podId, owner, VlanType.DirectAttached, network.getId(), requestedIp, false);
        if (ip == null) {
            s_logger.debug("There is no free public ip address");
            return null;
        }
        final Ip ipAddr = ip.getAddress();
        return ipAddr.addr();
    }

    @Override
    public String allocateGuestIP(final Network network, final String requestedIp) throws InsufficientAddressCapacityException {
        return acquireGuestIpAddress(network, requestedIp);
    }
}
