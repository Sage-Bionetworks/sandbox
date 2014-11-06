package controllers;

import java.util.List;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dynamodb.DynamoHealthDataRecord;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.models.GuidVersionHolder;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.Tracker;
import org.sagebionetworks.bridge.models.UserSession;
import org.sagebionetworks.bridge.models.healthdata.HealthDataKey;
import org.sagebionetworks.bridge.models.healthdata.HealthDataRecord;
import org.sagebionetworks.bridge.services.HealthDataService;
import org.sagebionetworks.bridge.validators.Validate;

import play.mvc.Result;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Function;
import com.google.common.collect.Lists;

public class HealthDataController extends BaseController {
    
    private static final Function<HealthDataRecord, GuidVersionHolder> TRANSFORMER = new Function<HealthDataRecord, GuidVersionHolder>() {
        @Override
        public GuidVersionHolder apply(HealthDataRecord record) {
            return new GuidVersionHolder(record.getGuid(), record.getVersion());
        }
    };

    private HealthDataService healthDataService;

    public void setHealthDataService(HealthDataService healthDataService) {
        this.healthDataService = healthDataService;
    }

    public Result appendHealthData(String trackerIdString) throws Exception {
        Long trackerId = BridgeUtils.parseLong(trackerIdString);
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);

        JsonNode node = requestToJSON(request());
        List<HealthDataRecord> records = Lists.newArrayListWithCapacity(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode child = node.get(i);
            Validate.jsonWithSchema(tracker, child);
            records.add(DynamoHealthDataRecord.fromJson(child));
        }

        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        List<HealthDataRecord> updatedRecords = healthDataService.appendHealthData(key, records);
        
        return okResult(Lists.transform(updatedRecords, TRANSFORMER));
    }

    public Result getHealthData(String trackerIdString, String startDate, String endDate) throws Exception {
        Long trackerId = BridgeUtils.parseLong(trackerIdString);
        if (startDate == null && endDate == null) {
            return getAllHealthData(trackerId);
        }
        long start, end;
        if (startDate == null) {
            start = -Long.MAX_VALUE;
        } else {
            start = DateUtils.convertToMillisFromEpoch(startDate);
        }
        if (endDate == null) {
            end = Long.MAX_VALUE;
        } else {
            end = DateUtils.convertToMillisFromEpoch(endDate);
        }
        return getHealthDataByDateRange(trackerId, start, end);
    }

    private Result getAllHealthData(Long trackerId) throws Exception {
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);

        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        List<HealthDataRecord> entries = healthDataService.getAllHealthData(key);
        return okResult(entries);
    }

    private Result getHealthDataByDateRange(Long trackerId, long startDate, long endDate) throws Exception {
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);
        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        List<HealthDataRecord> entries = healthDataService.getHealthDataByDateRange(key, startDate, endDate);
        return okResult(entries);
    }

    public Result getHealthDataRecord(String trackerIdString, String guid) throws Exception {
        Long trackerId = BridgeUtils.parseLong(trackerIdString);
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);
        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        HealthDataRecord record = healthDataService.getHealthDataRecord(key, guid);
        return okResult(record);
    }

    public Result updateHealthDataRecord(String trackerIdString, String guid) throws Exception {
        Long trackerId = BridgeUtils.parseLong(trackerIdString);
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);
        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        JsonNode node = requestToJSON(request());
        HealthDataRecord record = DynamoHealthDataRecord.fromJson(node);
        record.setGuid(guid);

        GuidVersionHolder holder = healthDataService.updateHealthDataRecord(key, record);
        return okResult(holder);
    }

    public Result deleteHealthDataRecord(String trackerIdString, String guid) throws Exception {
        Long trackerId = BridgeUtils.parseLong(trackerIdString);
        UserSession session = getAuthenticatedAndConsentedSession();
        Study study = studyService.getStudyByHostname(getHostname());
        Tracker tracker = study.getTrackerById(trackerId);
        HealthDataKey key = new HealthDataKey(study, tracker, session.getUser());

        healthDataService.deleteHealthDataRecord(key, guid);
        return okResult("Record deleted.");
    }

}
