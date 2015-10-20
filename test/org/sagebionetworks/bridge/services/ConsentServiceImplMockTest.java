package org.sagebionetworks.bridge.services;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.UserConsentDao;
import org.sagebionetworks.bridge.dao.ParticipantOption.SharingScope;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.User;
import org.sagebionetworks.bridge.models.accounts.UserConsent;
import org.sagebionetworks.bridge.models.studies.ConsentSignature;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyConsent;
import org.sagebionetworks.bridge.models.studies.StudyConsentView;
import org.sagebionetworks.bridge.redis.JedisOps;

public class ConsentServiceImplMockTest {

    private static final long UNIX_TIMESTAMP = DateUtils.getCurrentMillisFromEpoch();
    
    private ConsentServiceImpl consentService;

    private AccountDao accountDao;
    private JedisOps jedisOps;
    private ParticipantOptionsService optionsService;
    private SendMailService sendMailService;
    private StudyConsentService studyConsentService;
    private UserConsentDao userConsentDao;
    private ActivityEventService activityEventService;

    private Study study;
    private User user;
    private ConsentSignature consentSignature;
    
    @Before
    public void before() {
        accountDao = mock(AccountDao.class);
        jedisOps = mock(JedisOps.class);
        optionsService = mock(ParticipantOptionsService.class);
        sendMailService = mock(SendMailService.class);
        userConsentDao = mock(UserConsentDao.class);
        activityEventService = mock(ActivityEventService.class);
        studyConsentService = mock(StudyConsentService.class);

        consentService = new ConsentServiceImpl();
        consentService.setAccountDao(accountDao);
        consentService.setStringOps(jedisOps);
        consentService.setOptionsService(optionsService);
        consentService.setSendMailService(sendMailService);
        consentService.setUserConsentDao(userConsentDao);
        consentService.setActivityEventService(activityEventService);
        consentService.setStudyConsentService(studyConsentService);
        
        study = TestUtils.getValidStudy(ConsentServiceImplMockTest.class);
        user = new User();
        user.setHealthCode("BBB");
        consentSignature = ConsentSignature.create("Test User", "1990-01-01", null, null, UNIX_TIMESTAMP);
        
        Account account = mock(Account.class);
        when(accountDao.getAccount(any(Study.class), any(String.class))).thenReturn(account);
    }
    
    @Test
    public void activityEventFiredOnConsent() {
        UserConsent consent = mock(UserConsent.class);
        when(userConsentDao.giveConsent(any(String.class), any(StudyConsent.class), any(Long.class))).thenReturn(consent);
        
        StudyConsentView view = mock(StudyConsentView.class);
        when(studyConsentService.getActiveConsent(any(Study.class))).thenReturn(view);
        
        consentService.consentToResearch(study, user, consentSignature, SharingScope.NO_SHARING, false);
        
        verify(activityEventService).publishEnrollmentEvent(user.getHealthCode(), consent);
    }

    @Test
    public void noActivityEventIfTooYoung() {
        consentSignature = ConsentSignature.create("Test User", "2014-01-01", null, null, UNIX_TIMESTAMP);
        study.setMinAgeOfConsent(30); // Test is good until 2044. So there.
        
        try {
            consentService.consentToResearch(study, user, consentSignature, SharingScope.NO_SHARING, false);
            fail("Exception expected.");
        } catch(InvalidEntityException e) {
            verifyNoMoreInteractions(activityEventService);
        }
    }
    
    @Test
    public void noActivityEventIfAlreadyConsented() {
        user.setConsent(true);
        
        try {
            consentService.consentToResearch(study, user, consentSignature, SharingScope.NO_SHARING, false);
            fail("Exception expected.");
        } catch(EntityAlreadyExistsException e) {
            verifyNoMoreInteractions(activityEventService);
        }
    }
    
    @Test
    public void noActivityEventIfDaoFails() {
        when(userConsentDao.giveConsent(any(String.class), any(StudyConsent.class), any(Long.class))).thenThrow(new RuntimeException());
        
        try {
            consentService.consentToResearch(study, user, consentSignature, SharingScope.NO_SHARING, false);
            fail("Exception expected.");
        } catch(Throwable e) {
            verifyNoMoreInteractions(activityEventService);
        }
    }
    
}