package org.sagebionetworks.bridge;

import org.sagebionetworks.bridge.dao.StudyConsentDao;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.StudyConsent;
import org.sagebionetworks.bridge.services.StudyService;

public class StudyConsentBootstrapper {
    public StudyConsentBootstrapper(StudyService studyService, StudyConsentDao studyConsentDao) {
        for (Study study : studyService.getStudies()) {
            String path = "test/conf/secondstudy-consent.html";
            int minAge = 17;
            StudyConsent consent = studyConsentDao.getConsent(study.getKey());
            if (consent == null) {
                consent = studyConsentDao.addConsent(study.getKey(), path, minAge);
                studyConsentDao.setActive(consent, true);
            }
        }
    }
}