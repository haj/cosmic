package com.cloud.api.query.vo;

import com.cloud.api.Identity;
import com.cloud.api.InternalIdentity;
import com.cloud.host.Host.Type;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceState;
import com.cloud.utils.db.GenericDao;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;

/**
 * Host DB view.
 */
@Entity
@Table(name = "host_view")
public class HostJoinVO extends BaseViewVO implements InternalIdentity, Identity {

    @Column(name = "cluster_type")
    @Enumerated(value = EnumType.STRING)
    Cluster.ClusterType clusterType;
    @Id
    @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid;
    @Column(name = "name")
    private String name;
    @Column(name = "status")
    private Status status = null;
    @Column(name = "type")
    @Enumerated(value = EnumType.STRING)
    private Type type;
    @Column(name = "private_ip_address")
    private String privateIpAddress;
    @Column(name = "disconnected")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date disconnectedOn;
    @Column(name = "version")
    private String version;
    @Column(name = "hypervisor_type")
    @Enumerated(value = EnumType.STRING)
    private HypervisorType hypervisorType;
    @Column(name = "hypervisor_version")
    private String hypervisorVersion;
    @Column(name = "capabilities")
    private String caps;
    @Column(name = "last_ping")
    private long lastPinged;
    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;
    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;
    @Column(name = "resource_state")
    @Enumerated(value = EnumType.STRING)
    private ResourceState resourceState;
    @Column(name = "mgmt_server_id")
    private Long managementServerId;
    @Column(name = "cpu_sockets")
    private Integer cpuSockets;
    @Column(name = "cpus")
    private Integer cpus;
    @Column(name = "ram")
    private long totalMemory;
    @Column(name = "cluster_id")
    private long clusterId;
    @Column(name = "cluster_uuid")
    private String clusterUuid;
    @Column(name = "cluster_name")
    private String clusterName;
    @Column(name = "data_center_id")
    private long zoneId;

    @Column(name = "data_center_uuid")
    private String zoneUuid;

    @Column(name = "data_center_name")
    private String zoneName;

    @Column(name = "pod_id")
    private long podId;

    @Column(name = "pod_uuid")
    private String podUuid;

    @Column(name = "pod_name")
    private String podName;

    @Column(name = "guest_os_category_id")
    private long osCategoryId;

    @Column(name = "guest_os_category_uuid")
    private String osCategoryUuid;

    @Column(name = "guest_os_category_name")
    private String osCategoryName;

    @Column(name = "tag")
    private String tag;

    @Column(name = "memory_used_capacity")
    private long memUsedCapacity;

    @Column(name = "memory_reserved_capacity")
    private long memReservedCapacity;

    @Column(name = "cpu_used_capacity")
    private long cpuUsedCapacity;

    @Column(name = "cpu_reserved_capacity")
    private long cpuReservedCapacity;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "job_uuid")
    private String jobUuid;

    @Column(name = "job_status")
    private int jobStatus;

    public void setClusterType(final Cluster.ClusterType clusterType) {
        this.clusterType = clusterType;
    }

    public void setId(final long id) {
        this.id = id;
    }

    public void setUuid(final String uuid) {
        this.uuid = uuid;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setStatus(final Status status) {
        this.status = status;
    }

    public void setType(final Type type) {
        this.type = type;
    }

    public void setPrivateIpAddress(final String privateIpAddress) {
        this.privateIpAddress = privateIpAddress;
    }

    public void setDisconnectedOn(final Date disconnectedOn) {
        this.disconnectedOn = disconnectedOn;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public void setHypervisorType(final HypervisorType hypervisorType) {
        this.hypervisorType = hypervisorType;
    }

    public void setHypervisorVersion(final String hypervisorVersion) {
        this.hypervisorVersion = hypervisorVersion;
    }

    public void setCaps(final String caps) {
        this.caps = caps;
    }

    public void setLastPinged(final long lastPinged) {
        this.lastPinged = lastPinged;
    }

    public void setCreated(final Date created) {
        this.created = created;
    }

    public void setRemoved(final Date removed) {
        this.removed = removed;
    }

    public void setResourceState(final ResourceState resourceState) {
        this.resourceState = resourceState;
    }

    public void setManagementServerId(final Long managementServerId) {
        this.managementServerId = managementServerId;
    }

    public void setCpuSockets(final Integer cpuSockets) {
        this.cpuSockets = cpuSockets;
    }

    public void setCpus(final Integer cpus) {
        this.cpus = cpus;
    }

    public void setTotalMemory(final long totalMemory) {
        this.totalMemory = totalMemory;
    }

    public void setClusterId(final long clusterId) {
        this.clusterId = clusterId;
    }

    public void setClusterUuid(final String clusterUuid) {
        this.clusterUuid = clusterUuid;
    }

    public void setClusterName(final String clusterName) {
        this.clusterName = clusterName;
    }

    public void setZoneId(final long zoneId) {
        this.zoneId = zoneId;
    }

    public void setZoneUuid(final String zoneUuid) {
        this.zoneUuid = zoneUuid;
    }

    public void setZoneName(final String zoneName) {
        this.zoneName = zoneName;
    }

    public void setPodId(final long podId) {
        this.podId = podId;
    }

    public void setPodUuid(final String podUuid) {
        this.podUuid = podUuid;
    }

    public void setPodName(final String podName) {
        this.podName = podName;
    }

    public void setOsCategoryId(final long osCategoryId) {
        this.osCategoryId = osCategoryId;
    }

    public void setOsCategoryUuid(final String osCategoryUuid) {
        this.osCategoryUuid = osCategoryUuid;
    }

    public void setOsCategoryName(final String osCategoryName) {
        this.osCategoryName = osCategoryName;
    }

    public void setTag(final String tag) {
        this.tag = tag;
    }

    public void setMemUsedCapacity(final long memUsedCapacity) {
        this.memUsedCapacity = memUsedCapacity;
    }

    public void setMemReservedCapacity(final long memReservedCapacity) {
        this.memReservedCapacity = memReservedCapacity;
    }

    public void setCpuUsedCapacity(final long cpuUsedCapacity) {
        this.cpuUsedCapacity = cpuUsedCapacity;
    }

    public void setCpuReservedCapacity(final long cpuReservedCapacity) {
        this.cpuReservedCapacity = cpuReservedCapacity;
    }

    public void setJobId(final Long jobId) {
        this.jobId = jobId;
    }

    public void setJobUuid(final String jobUuid) {
        this.jobUuid = jobUuid;
    }

    public void setJobStatus(final int jobStatus) {
        this.jobStatus = jobStatus;
    }

    @Override
    public long getId() {
        return this.id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public String getVersion() {
        return version;
    }

    public long getZoneId() {
        return zoneId;
    }

    public String getZoneUuid() {
        return zoneUuid;
    }

    public String getZoneName() {
        return zoneName;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public Type getType() {
        return type;
    }

    public String getPrivateIpAddress() {
        return privateIpAddress;
    }

    public Date getDisconnectedOn() {
        return disconnectedOn;
    }

    public HypervisorType getHypervisorType() {
        return hypervisorType;
    }

    public String getHypervisorVersion() {
        return hypervisorVersion;
    }

    public String getCapabilities() {
        return caps;
    }

    public long getLastPinged() {
        return lastPinged;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    public ResourceState getResourceState() {
        return resourceState;
    }

    public Long getManagementServerId() {
        return managementServerId;
    }

    public Integer getCpuSockets() {
        return cpuSockets;
    }

    public Integer getCpus() {
        return cpus;
    }

    public long getTotalMemory() {
        return totalMemory;
    }

    public long getClusterId() {
        return clusterId;
    }

    public String getClusterUuid() {
        return clusterUuid;
    }

    public String getClusterName() {
        return clusterName;
    }

    public Cluster.ClusterType getClusterType() {
        return clusterType;
    }

    public long getOsCategoryId() {
        return osCategoryId;
    }

    public String getOsCategoryUuid() {
        return osCategoryUuid;
    }

    public String getOsCategoryName() {
        return osCategoryName;
    }

    public Long getJobId() {
        return jobId;
    }

    public String getJobUuid() {
        return jobUuid;
    }

    public int getJobStatus() {
        return jobStatus;
    }

    public long getPodId() {
        return podId;
    }

    public String getPodUuid() {
        return podUuid;
    }

    public String getPodName() {
        return podName;
    }

    public long getMemUsedCapacity() {
        return memUsedCapacity;
    }

    public long getMemReservedCapacity() {
        return memReservedCapacity;
    }

    public long getCpuUsedCapacity() {
        return cpuUsedCapacity;
    }

    public long getCpuReservedCapacity() {
        return cpuReservedCapacity;
    }

    public String getTag() {
        return tag;
    }
}
