package com.cloud.metadata;

import com.cloud.api.ResourceDetail;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.resourcedetail.ResourceDetailsDao;
import com.cloud.resourcedetail.dao.DiskOfferingDetailsDao;
import com.cloud.resourcedetail.dao.FirewallRuleDetailsDao;
import com.cloud.resourcedetail.dao.LBHealthCheckPolicyDetailsDao;
import com.cloud.resourcedetail.dao.LBStickinessPolicyDetailsDao;
import com.cloud.resourcedetail.dao.NetworkACLItemDetailsDao;
import com.cloud.resourcedetail.dao.NetworkACLListDetailsDao;
import com.cloud.resourcedetail.dao.RemoteAccessVpnDetailsDao;
import com.cloud.resourcedetail.dao.Site2SiteCustomerGatewayDetailsDao;
import com.cloud.resourcedetail.dao.Site2SiteVpnConnectionDetailsDao;
import com.cloud.resourcedetail.dao.Site2SiteVpnGatewayDetailsDao;
import com.cloud.resourcedetail.dao.UserDetailsDao;
import com.cloud.resourcedetail.dao.UserIpAddressDetailsDao;
import com.cloud.resourcedetail.dao.VpcDetailsDao;
import com.cloud.resourcedetail.dao.VpcGatewayDetailsDao;
import com.cloud.server.ResourceMetaDataService;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.server.TaggedResourceService;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.datastore.db.StoragePoolDetailsDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.InvalidParameterValueException;
import com.cloud.vm.dao.NicDetailsDao;
import com.cloud.vm.dao.UserVmDetailsDao;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResourceMetaDataManagerImpl extends ManagerBase implements ResourceMetaDataService, ResourceMetaDataManager {
    public static final Logger s_logger = LoggerFactory.getLogger(ResourceMetaDataManagerImpl.class);
    private static final Map<ResourceObjectType, ResourceDetailsDao<? extends ResourceDetail>> s_daoMap = new HashMap<>();
    @Inject
    VolumeDetailsDao _volumeDetailDao;
    @Inject
    NicDetailsDao _nicDetailDao;
    @Inject
    UserVmDetailsDao _userVmDetailDao;
    @Inject
    DataCenterDetailsDao _dcDetailsDao;
    @Inject
    NetworkDetailsDao _networkDetailsDao;
    @Inject
    TaggedResourceService _taggedResourceMgr;
    @Inject
    VMTemplateDetailsDao _templateDetailsDao;
    @Inject
    ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    StoragePoolDetailsDao _storageDetailsDao;
    @Inject
    FirewallRuleDetailsDao _firewallRuleDetailsDao;
    @Inject
    UserIpAddressDetailsDao _userIpAddressDetailsDao;
    @Inject
    RemoteAccessVpnDetailsDao _vpnDetailsDao;
    @Inject
    VpcDetailsDao _vpcDetailsDao;
    @Inject
    VpcGatewayDetailsDao _vpcGatewayDetailsDao;
    @Inject
    NetworkACLListDetailsDao _networkACLListDetailsDao;
    @Inject
    NetworkACLItemDetailsDao _networkACLDetailsDao;
    @Inject
    Site2SiteVpnGatewayDetailsDao _vpnGatewayDetailsDao;
    @Inject
    Site2SiteCustomerGatewayDetailsDao _customerGatewayDetailsDao;
    @Inject
    Site2SiteVpnConnectionDetailsDao _vpnConnectionDetailsDao;
    @Inject
    DiskOfferingDetailsDao _diskOfferingDetailsDao;
    @Inject
    UserDetailsDao _userDetailsDao;
    @Inject
    LBStickinessPolicyDetailsDao _stickinessPolicyDetailsDao;
    @Inject
    LBHealthCheckPolicyDetailsDao _healthcheckPolicyDetailsDao;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        s_daoMap.put(ResourceObjectType.UserVm, _userVmDetailDao);
        s_daoMap.put(ResourceObjectType.Volume, _volumeDetailDao);
        s_daoMap.put(ResourceObjectType.Template, _templateDetailsDao);
        s_daoMap.put(ResourceObjectType.Network, _networkDetailsDao);
        s_daoMap.put(ResourceObjectType.Nic, _nicDetailDao);
        s_daoMap.put(ResourceObjectType.ServiceOffering, _serviceOfferingDetailsDao);
        s_daoMap.put(ResourceObjectType.Zone, _dcDetailsDao);
        s_daoMap.put(ResourceObjectType.Storage, _storageDetailsDao);
        s_daoMap.put(ResourceObjectType.FirewallRule, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.PublicIpAddress, _userIpAddressDetailsDao);
        s_daoMap.put(ResourceObjectType.PortForwardingRule, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.LoadBalancer, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.RemoteAccessVpn, _vpnDetailsDao);
        s_daoMap.put(ResourceObjectType.Vpc, _vpcDetailsDao);
        s_daoMap.put(ResourceObjectType.PrivateGateway, _vpcGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.NetworkACLList, _networkACLListDetailsDao);
        s_daoMap.put(ResourceObjectType.NetworkACL, _networkACLDetailsDao);
        s_daoMap.put(ResourceObjectType.VpnGateway, _vpnGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.CustomerGateway, _customerGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.VpnConnection, _vpnConnectionDetailsDao);
        s_daoMap.put(ResourceObjectType.DiskOffering, _diskOfferingDetailsDao);
        s_daoMap.put(ResourceObjectType.User, _userDetailsDao);
        s_daoMap.put(ResourceObjectType.LBStickinessPolicy, _stickinessPolicyDetailsDao);
        s_daoMap.put(ResourceObjectType.LBHealthCheckPolicy, _healthcheckPolicyDetailsDao);

        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_RESOURCE_DETAILS_CREATE, eventDescription = "creating resource meta data")
    public boolean addResourceMetaData(final String resourceId, final ResourceObjectType resourceType, final Map<String, String> details, final boolean forDisplay) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(final TransactionStatus status) {
                for (final String key : details.keySet()) {
                    final String value = details.get(key);

                    if (value == null || value.isEmpty()) {
                        throw new InvalidParameterValueException("Value for the key " + key + " is either null or empty");
                    }

                    final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
                    newDetailDaoHelper.addDetail(_taggedResourceMgr.getResourceId(resourceId, resourceType), key, value, forDisplay);
                }

                return true;
            }
        });
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_RESOURCE_DETAILS_DELETE, eventDescription = "deleting resource meta data")
    public boolean deleteResourceMetaData(final String resourceId, final ResourceObjectType resourceType, final String key) {
        final long id = _taggedResourceMgr.getResourceId(resourceId, resourceType);

        final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        newDetailDaoHelper.removeDetail(id, key);

        return true;
    }

    @Override
    public ResourceDetail getDetail(final long resourceId, final ResourceObjectType resourceType, final String key) {
        final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetail(resourceId, key);
    }

    @Override
    public List<? extends ResourceDetail> getDetails(final ResourceObjectType resourceType, final String key, final String value, final Boolean forDisplay) {
        final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetails(key, value, forDisplay);
    }

    @Override
    public Map<String, String> getDetailsMap(final long resourceId, final ResourceObjectType resourceType, final Boolean forDisplay) {
        final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetailsMap(resourceId, forDisplay);
    }

    @Override
    public List<? extends ResourceDetail> getDetailsList(final long resourceId, final ResourceObjectType resourceType, final Boolean forDisplay) {
        final DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetailsList(resourceId, forDisplay);
    }

    private class DetailDaoHelper {
        private final ResourceObjectType resourceType;
        private final ResourceDetailsDao<? super ResourceDetail> dao;

        private DetailDaoHelper(final ResourceObjectType resourceType) {
            if (!resourceType.resourceMetadataSupport()) {
                throw new UnsupportedOperationException("ResourceType " + resourceType + " doesn't support metadata");
            }
            this.resourceType = resourceType;
            final ResourceDetailsDao<?> dao = s_daoMap.get(resourceType);
            if (dao == null) {
                throw new UnsupportedOperationException("ResourceType " + resourceType + " doesn't support metadata");
            }
            this.dao = (ResourceDetailsDao) s_daoMap.get(resourceType);
        }

        private void removeDetail(final long resourceId, final String key) {
            dao.removeDetail(resourceId, key);
        }

        private ResourceDetail getDetail(final long resourceId, final String key) {
            return dao.findDetail(resourceId, key);
        }

        private List<? extends ResourceDetail> getDetails(final String key, final String value, final Boolean forDisplay) {
            return dao.findDetails(key, value, forDisplay);
        }

        private void addDetail(final long resourceId, final String key, final String value, final boolean forDisplay) {
            dao.addDetail(resourceId, key, value, forDisplay);
        }

        private Map<String, String> getDetailsMap(final long resourceId, final Boolean forDisplay) {
            if (forDisplay == null) {
                return dao.listDetailsKeyPairs(resourceId);
            } else {
                return dao.listDetailsKeyPairs(resourceId, forDisplay);
            }
        }

        private List<? extends ResourceDetail> getDetailsList(final long resourceId, final Boolean forDisplay) {
            if (forDisplay == null) {
                return dao.listDetails(resourceId);
            } else {
                return dao.listDetails(resourceId, forDisplay);
            }
        }
    }
}
