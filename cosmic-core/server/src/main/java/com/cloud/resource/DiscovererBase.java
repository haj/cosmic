package com.cloud.resource;

import com.cloud.configuration.Config;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.framework.config.dao.ConfigurationDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.net.UrlUtil;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DiscovererBase extends AdapterBase implements Discoverer {
    private static final Logger s_logger = LoggerFactory.getLogger(DiscovererBase.class);
    protected Map<String, String> _params;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected NetworkModel _networkMgr;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected ResourceManager _resourceMgr;
    @Inject
    protected DataCenterDao _dcDao;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _params = _configDao.getConfiguration(params);

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

    protected Map<String, String> resolveInputParameters(final URL url) {
        final Map<String, String> params = UrlUtil.parseQueryParameters(url);

        return null;
    }

    @Override
    public void putParam(final Map<String, String> params) {
        if (_params == null) {
            _params = new HashMap<>();
        }
        _params.putAll(params);
    }

    @Override
    public ServerResource reloadResource(final HostVO host) {
        final String resourceName = host.getResource();
        final ServerResource resource = getResource(resourceName);

        if (resource != null) {
            _hostDao.loadDetails(host);
            updateNetworkLabels(host);

            final HashMap<String, Object> params = buildConfigParams(host);
            try {
                resource.configure(host.getName(), params);
            } catch (final ConfigurationException e) {
                s_logger.warn("Unable to configure resource due to " + e.getMessage());
                return null;
            }
            if (!resource.start()) {
                s_logger.warn("Unable to start the resource");
                return null;
            }
        }
        return resource;
    }

    protected ServerResource getResource(final String resourceName) {
        ServerResource resource = null;
        try {
            final Class<?> clazz = Class.forName(resourceName);
            final Constructor constructor = clazz.getConstructor();
            resource = (ServerResource) constructor.newInstance();
        } catch (final ClassNotFoundException e) {
            s_logger.warn("Unable to find class " + resourceName, e);
        } catch (final InstantiationException e) {
            s_logger.warn("Unablet to instantiate class " + resourceName, e);
        } catch (final IllegalAccessException e) {
            s_logger.warn("Illegal access " + resourceName, e);
        } catch (final SecurityException e) {
            s_logger.warn("Security error on " + resourceName, e);
        } catch (final NoSuchMethodException e) {
            s_logger.warn("NoSuchMethodException error on " + resourceName, e);
        } catch (final IllegalArgumentException e) {
            s_logger.warn("IllegalArgumentException error on " + resourceName, e);
        } catch (final InvocationTargetException e) {
            s_logger.warn("InvocationTargetException error on " + resourceName, e);
        }

        return resource;
    }

    private void updateNetworkLabels(final HostVO host) {
        //check if networkLabels need to be updated in details
        //we send only private and storage network label to the resource.
        final String privateNetworkLabel = _networkMgr.getDefaultManagementTrafficLabel(host.getDataCenterId(), host.getHypervisorType());
        final String storageNetworkLabel = _networkMgr.getDefaultStorageTrafficLabel(host.getDataCenterId(), host.getHypervisorType());

        final String privateDevice = host.getDetail("private.network.device");
        final String storageDevice = host.getDetail("storage.network.device1");

        boolean update = false;

        if (privateNetworkLabel != null && !privateNetworkLabel.equalsIgnoreCase(privateDevice)) {
            host.setDetail("private.network.device", privateNetworkLabel);
            update = true;
        }
        if (storageNetworkLabel != null && !storageNetworkLabel.equalsIgnoreCase(storageDevice)) {
            host.setDetail("storage.network.device1", storageNetworkLabel);
            update = true;
        }
        if (update) {
            _hostDao.saveDetails(host);
        }
    }

    protected HashMap<String, Object> buildConfigParams(final HostVO host) {
        final HashMap<String, Object> params = new HashMap<>(host.getDetails().size() + 5);
        params.putAll(host.getDetails());

        params.put("guid", host.getGuid());
        params.put("zone", Long.toString(host.getDataCenterId()));
        if (host.getPodId() != null) {
            params.put("pod", Long.toString(host.getPodId()));
        }
        if (host.getClusterId() != null) {
            params.put("cluster", Long.toString(host.getClusterId()));
            String guid = null;
            final ClusterVO cluster = _clusterDao.findById(host.getClusterId());
            if (cluster.getGuid() == null) {
                guid = host.getDetail("pool");
            } else {
                guid = cluster.getGuid();
            }
            if (guid != null && !guid.isEmpty()) {
                params.put("pool", guid);
            }
        }

        params.put("ipaddress", host.getPrivateIpAddress());
        params.put("secondary.storage.vm", "false");
        params.put("max.template.iso.size", _configDao.getValue(Config.MaxTemplateAndIsoSize.toString()));
        params.put("migratewait", _configDao.getValue(Config.MigrateWait.toString()));
        params.put(Config.XenServerMaxNics.toString().toLowerCase(), _configDao.getValue(Config.XenServerMaxNics.toString()));
        params.put(Config.XenServerHeartBeatInterval.toString().toLowerCase(), _configDao.getValue(Config.XenServerHeartBeatInterval.toString()));
        params.put(Config.XenServerHeartBeatTimeout.toString().toLowerCase(), _configDao.getValue(Config.XenServerHeartBeatTimeout.toString()));
        params.put("router.aggregation.command.each.timeout", _configDao.getValue(Config.RouterAggregationCommandEachTimeout.toString()));

        return params;
    }
}
