package org.sagebionetworks.bridge;

import org.joda.time.DateTimeZone;

public class BridgeConstants {

    public static final String SESSION_TOKEN_HEADER = "Bridge-Session";

    public static final String BRIDGE_STUDY_HEADER = "Bridge-Study";
    
    public static final String BRIDGE_HOST_HEADER = "Bridge-Host";
    
    public static final String CUSTOM_DATA_HEALTH_CODE_SUFFIX = "_code";

    public static final String CUSTOM_DATA_CONSENT_SIGNATURE_SUFFIX = "_consent_signature";

    public static final String CUSTOM_DATA_VERSION = "version";

    public static final String ADMIN_GROUP = "admin";

    public static final String TEST_USERS_GROUP = "test_users";

    public static final DateTimeZone LOCAL_TIME_ZONE = DateTimeZone.forID("America/Los_Angeles");

    // 24 hrs after last activity
    public static final int BRIDGE_SESSION_EXPIRE_IN_SECONDS = 24 * 60 * 60;

    public static final int BRIDGE_UPDATE_ATTEMPT_EXPIRE_IN_SECONDS = 5 * 60;
    
    public static final int BRIDGE_VIEW_EXPIRE_IN_SECONDS = 5 * 60 * 60;

    public static final String SCHEDULE_STRATEGY_PACKAGE = "org.sagebionetworks.bridge.models.schedules.";

    public static final String PHONE_ATTRIBUTE = "phone";

    public static final String ASSETS_HOST = "assets.sagebridge.org";
    
    public static final String JSON_MIME_TYPE = "application/json; charset=UTF-8";
}
