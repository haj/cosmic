package com.cloud.api.response;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseResponse;
import com.cloud.api.EntityReference;
import com.cloud.dc.Pod;
import com.cloud.serializer.Param;

import java.util.List;

import com.google.gson.annotations.SerializedName;

@EntityReference(value = Pod.class)
public class PodResponse extends BaseResponse {
    @SerializedName("id")
    @Param(description = "the ID of the Pod")
    private String id;

    @SerializedName("name")
    @Param(description = "the name of the Pod")
    private String name;

    @SerializedName("zoneid")
    @Param(description = "the Zone ID of the Pod")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the Zone name of the Pod")
    private String zoneName;

    @SerializedName("gateway")
    @Param(description = "the gateway of the Pod")
    private String gateway;

    @SerializedName("netmask")
    @Param(description = "the netmask of the Pod")
    private String netmask;

    @SerializedName("startip")
    @Param(description = "the starting IP for the Pod")
    private String startIp;

    @SerializedName("endip")
    @Param(description = "the ending IP for the Pod")
    private String endIp;

    @SerializedName("allocationstate")
    @Param(description = "the allocation state of the Pod")
    private String allocationState;

    @SerializedName("capacity")
    @Param(description = "the capacity of the Pod", responseObject = CapacityResponse.class)
    private List<CapacityResponse> capacitites;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(final String zoneId) {
        this.zoneId = zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(final String zoneName) {
        this.zoneName = zoneName;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(final String gateway) {
        this.gateway = gateway;
    }

    public String getNetmask() {
        return netmask;
    }

    public void setNetmask(final String netmask) {
        this.netmask = netmask;
    }

    public String getStartIp() {
        return startIp;
    }

    public void setStartIp(final String startIp) {
        this.startIp = startIp;
    }

    public String getEndIp() {
        return endIp;
    }

    public void setEndIp(final String endIp) {
        this.endIp = endIp;
    }

    public String getAllocationState() {
        return allocationState;
    }

    public void setAllocationState(final String allocationState) {
        this.allocationState = allocationState;
    }

    public List<CapacityResponse> getCapacitites() {
        return capacitites;
    }

    public void setCapacitites(final List<CapacityResponse> capacitites) {
        this.capacitites = capacitites;
    }
}
