package com.cloud.network.vpn;

import com.cloud.api.command.user.vpn.ListRemoteAccessVpnsCmd;
import com.cloud.api.command.user.vpn.ListVpnUsersCmd;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.VpnUser;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

import java.util.List;

public interface RemoteAccessVpnService {
    static final String RemoteAccessVpnClientIpRangeCK = "remote.access.vpn.client.iprange";

    RemoteAccessVpn createRemoteAccessVpn(long vpnServerAddressId, String ipRange, Boolean forDisplay) throws NetworkRuleConflictException;

    boolean destroyRemoteAccessVpnForIp(long ipId, Account caller) throws ResourceUnavailableException;

    RemoteAccessVpn startRemoteAccessVpn(long vpnServerAddressId) throws ResourceUnavailableException;

    VpnUser addVpnUser(long vpnOwnerId, String userName, String password);

    boolean removeVpnUser(long vpnOwnerId, String userName, Account caller);

    List<? extends VpnUser> listVpnUsers(long vpnOwnerId, String userName);

    boolean applyVpnUsers(long vpnOwnerId, String userName);

    Pair<List<? extends RemoteAccessVpn>, Integer> searchForRemoteAccessVpns(ListRemoteAccessVpnsCmd cmd);

    Pair<List<? extends VpnUser>, Integer> searchForVpnUsers(ListVpnUsersCmd cmd);

    List<? extends RemoteAccessVpn> listRemoteAccessVpns(long networkId);

    RemoteAccessVpn getRemoteAccessVpn(long vpnAddrId);

    RemoteAccessVpn getRemoteAccessVpnById(long vpnId);

    RemoteAccessVpn updateRemoteAccessVpn(long id, String customId, Boolean forDisplay);
}
