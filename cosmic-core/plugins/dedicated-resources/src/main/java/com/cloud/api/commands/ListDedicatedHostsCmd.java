package com.cloud.api.commands;

import com.cloud.affinity.AffinityGroupResponse;
import com.cloud.api.APICommand;
import com.cloud.api.APICommandGroup;
import com.cloud.api.ApiConstants;
import com.cloud.api.ApiErrorCode;
import com.cloud.api.BaseListCmd;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.DedicateHostResponse;
import com.cloud.api.response.DomainResponse;
import com.cloud.api.response.HostResponse;
import com.cloud.api.response.ListResponse;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.DedicatedResources;
import com.cloud.dedicated.DedicatedService;
import com.cloud.utils.Pair;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "listDedicatedHosts", group = APICommandGroup.HostService, description = "Lists dedicated hosts.", responseObject = DedicateHostResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListDedicatedHostsCmd extends BaseListCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(ListDedicatedHostsCmd.class.getName());

    private static final String s_name = "listdedicatedhostsresponse";
    @Inject
    DedicatedService dedicatedService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, description = "the ID of the host")
    private Long hostId;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "the ID of the domain associated with the host")
    private Long domainId;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "the name of the account associated with the host. Must be used with domainId.")
    private String accountName;

    @Parameter(name = ApiConstants.AFFINITY_GROUP_ID,
            type = CommandType.UUID,
            entityType = AffinityGroupResponse.class,
            description = "list dedicated hosts by affinity group")
    private Long affinityGroupId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getHostId() {
        return hostId;
    }

    public Long getDomainId() {
        return domainId;
    }

    public String getAccountName() {
        return accountName;
    }

    public Long getAffinityGroupId() {
        return affinityGroupId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////l
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        final Pair<List<? extends DedicatedResourceVO>, Integer> result = dedicatedService.listDedicatedHosts(this);
        final ListResponse<DedicateHostResponse> response = new ListResponse<>();
        final List<DedicateHostResponse> Responses = new ArrayList<>();
        if (result != null) {
            for (final DedicatedResources resource : result.first()) {
                final DedicateHostResponse hostResponse = dedicatedService.createDedicateHostResponse(resource);
                Responses.add(hostResponse);
            }
            response.setResponses(Responses, result.second());
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to list dedicated hosts");
        }
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
