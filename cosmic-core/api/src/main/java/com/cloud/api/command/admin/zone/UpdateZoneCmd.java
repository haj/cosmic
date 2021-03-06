package com.cloud.api.command.admin.zone;

import com.cloud.api.APICommand;
import com.cloud.api.APICommandGroup;
import com.cloud.api.ApiConstants;
import com.cloud.api.ApiErrorCode;
import com.cloud.api.BaseCmd;
import com.cloud.api.Parameter;
import com.cloud.api.ResponseObject.ResponseView;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.ZoneResponse;
import com.cloud.context.CallContext;
import com.cloud.dc.DataCenter;
import com.cloud.user.Account;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "updateZone", group = APICommandGroup.ZoneService, description = "Updates a Zone.", responseObject = ZoneResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class UpdateZoneCmd extends BaseCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(UpdateZoneCmd.class.getName());

    private static final String s_name = "updatezoneresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.DNS1, type = CommandType.STRING, description = "the first DNS for the Zone")
    private String dns1;

    @Parameter(name = ApiConstants.DNS2, type = CommandType.STRING, description = "the second DNS for the Zone")
    private String dns2;

    @Parameter(name = ApiConstants.IP6_DNS1, type = CommandType.STRING, description = "the first DNS for IPv6 network in the Zone")
    private String ip6Dns1;

    @Parameter(name = ApiConstants.IP6_DNS2, type = CommandType.STRING, description = "the second DNS for IPv6 network in the Zone")
    private String ip6Dns2;

    @Parameter(name = ApiConstants.GUEST_CIDR_ADDRESS, type = CommandType.STRING, description = "the guest CIDR address for the Zone")
    private String guestCidrAddress;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true, description = "the ID of the Zone")
    private Long id;

    @Parameter(name = ApiConstants.INTERNAL_DNS1, type = CommandType.STRING, description = "the first internal DNS for the Zone")
    private String internalDns1;

    @Parameter(name = ApiConstants.INTERNAL_DNS2, type = CommandType.STRING, description = "the second internal DNS for the Zone")
    private String internalDns2;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "the name of the Zone")
    private String zoneName;

    @Parameter(name = ApiConstants.IS_PUBLIC, type = CommandType.BOOLEAN, description = "updates a private zone to public if set, but not vice-versa")
    private Boolean isPublic;

    @Parameter(name = ApiConstants.ALLOCATION_STATE, type = CommandType.STRING, description = "Allocation state of this cluster for allocation of new resources")
    private String allocationState;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP, description = "the details for the Zone")
    private Map details;

    @Parameter(name = ApiConstants.DHCP_PROVIDER, type = CommandType.STRING, description = "the dhcp Provider for the Zone")
    private String dhcpProvider;

    @Parameter(name = ApiConstants.DOMAIN,
            type = CommandType.STRING,
            description = "Network domain name for the networks in the zone; empty string will update domain with NULL value")
    private String domain;

    @Parameter(name = ApiConstants.DNS_SEARCH_ORDER, type = CommandType.LIST, collectionType = CommandType.STRING, description = "the dns search order list")
    private List<String> dnsSearchOrder;

    @Parameter(name = ApiConstants.LOCAL_STORAGE_ENABLED, type = CommandType.BOOLEAN, description = "true if local storage offering enabled, false otherwise")
    private Boolean localStorageEnabled;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getDns1() {
        return dns1;
    }

    public String getDns2() {
        return dns2;
    }

    public String getGuestCidrAddress() {
        return guestCidrAddress;
    }

    public String getIp6Dns1() {
        return ip6Dns1;
    }

    public String getIp6Dns2() {
        return ip6Dns2;
    }

    public String getInternalDns1() {
        return internalDns1;
    }

    public String getInternalDns2() {
        return internalDns2;
    }

    public String getZoneName() {
        return zoneName;
    }

    public Boolean isPublic() {
        return isPublic;
    }

    public String getAllocationState() {
        return allocationState;
    }

    public Map getDetails() {
        return details;
    }

    public String getDhcpProvider() {
        return dhcpProvider;
    }

    public String getDomain() {
        return domain;
    }

    public List<String> getDnsSearchOrder() {
        return dnsSearchOrder;
    }

    public Boolean getLocalStorageEnabled() {
        return localStorageEnabled;
    }

    @Override
    public void execute() {
        CallContext.current().setEventDetails("Zone Id: " + getId());
        final DataCenter result = _configService.editZone(this);
        if (result != null) {
            final ZoneResponse response = _responseGenerator.createZoneResponse(ResponseView.Full, result, false);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update zone; internal error.");
        }
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
