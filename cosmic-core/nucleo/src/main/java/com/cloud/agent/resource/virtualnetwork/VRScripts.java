package com.cloud.agent.resource.virtualnetwork;

public class VRScripts {
    public final static String CONFIG_PERSIST_LOCATION = "/var/cache/cloud/";
    public static final String NETWORK_OVERVIEW_CONFIG = "network_overview.json";
    public static final String VM_OVERVIEW_CONFIG = "vm_overview.json";
    public final static String NETWORK_ACL_CONFIG = "network_acl.json";
    public final static String PUBLIC_IP_ACL_CONFIG = "public_ip_acl.json";
    public final static String VM_PASSWORD_CONFIG = "vm_password.json";
    public static final String FORWARDING_RULES_CONFIG = "forwarding_rules.json";
    public static final String FIREWALL_RULES_CONFIG = "firewall_rules.json";
    public static final String STATICNAT_RULES_CONFIG = "staticnat_rules.json";
    public static final String LOAD_BALANCER_CONFIG = "load_balancer.json";
    public static final String VR_CONFIG = "vr.json";

    public final static int DEFAULT_EXECUTEINVR_TIMEOUT = 120; //Seconds

    // Present inside the router
    public static final String UPDATE_CONFIG = "bin/update_config.py";
    public static final String S2SVPN_CHECK = "scripts/checkbatchs2svpn.sh";
    public static final String RVR_CHECK = "scripts/checkrouter.sh";
    public static final String VERSION = "scripts/get_template_version.sh";
    public static final String VPC_NETUSAGE = "scripts/vpc_netusage.sh";

    // Present on the KVM hypervisor
    public static final String UPDATE_HOST_PASSWD = "update_host_passwd.sh";
}
