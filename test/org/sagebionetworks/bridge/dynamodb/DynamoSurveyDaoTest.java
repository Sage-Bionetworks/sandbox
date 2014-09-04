package org.sagebionetworks.bridge.dynamodb;

import static org.junit.Assert.*;

import java.util.List;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.dao.ConcurrentModificationException;
import org.sagebionetworks.bridge.dao.PublishedSurveyException;
import org.sagebionetworks.bridge.dao.SurveyNotFoundException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.surveys.SurveyQuestion;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class DynamoSurveyDaoTest {

    @Resource
    DynamoSurveyDao surveyDao;
    
    private static final String STUDY_KEY = TestConstants.SECOND_STUDY.getKey();
    
    @Before
    public void before() {
        DynamoInitializer.init("org.sagebionetworks.bridge.dynamodb");
        DynamoTestUtil.clearTable(DynamoSurvey.class, 
            "studyKey", "identifier", "name", "ownerGroup", "published", "version");
        DynamoTestUtil.clearTable(DynamoSurveyQuestion.class, "guid", "identifier", "data");
    }
    
    @After
    public void after() {
        List<Survey> surveys = surveyDao.getSurveys(STUDY_KEY);
        for (Survey survey : surveys) {
            surveyDao.closeSurvey(survey.getGuid(), survey.getVersionedOn());
            surveyDao.deleteSurvey(survey.getGuid(), survey.getVersionedOn());
        }
    }

    private Survey constructTestSurvey() {
        return constructTestSurvey("Health Overview Test Survey");
    }
    
    private Survey constructTestSurvey(String name) {
        Survey survey = new DynamoSurvey();
        survey.setName(name);
        survey.setIdentifier("overview");
        survey.setStudyKey(STUDY_KEY);
        return survey;
    }
    
    // CREATE SURVEY
    
    @Test(expected=BridgeServiceException.class)
    public void createPreventsEmptyStudyKey() {
        Survey survey = constructTestSurvey();
        survey.setStudyKey(null);
        surveyDao.createSurvey(survey);
    }
    
    @Test(expected=BridgeServiceException.class)
    public void createPreventsNoIdentifier() {
        Survey survey = constructTestSurvey();
        survey.setIdentifier(null);
        surveyDao.createSurvey(survey);
    }
    
    @Test(expected=BridgeServiceException.class)
    public void createPreventsQuestionWithNoIdentifier() {
        Survey survey = constructTestSurvey();
        
        SurveyQuestion question = new DynamoSurveyQuestion();
        question.setSurveyCompoundKey("AAA");
        survey.getQuestions().add(question);
        
        surveyDao.createSurvey(survey);
    }
    
    @Test(expected=BridgeServiceException.class)
    public void createPreventsRecreatingASurvey() {
        Survey survey = constructTestSurvey();
        
        survey = surveyDao.createSurvey(survey);
        surveyDao.createSurvey(survey);
    }
    
    @Test
    public void crudSurvey() {
        Survey survey = constructTestSurvey();
        
        SurveyQuestion question = new DynamoSurveyQuestion();
        question.setIdentifier("age");
        survey.getQuestions().add(question);
        
        question = new DynamoSurveyQuestion();
        question.setIdentifier("gender");
        survey.getQuestions().add(question);
        
        survey = surveyDao.createSurvey(survey);
        
        assertTrue("Survey has a guid", survey.getGuid() != null);
        assertTrue("Survey has been versioned", survey.getVersionedOn() != 0L);
        assertTrue("Question #1 has a guid", survey.getQuestions().get(0).getGuid() != null);
        assertTrue("Question #2 has a guid", survey.getQuestions().get(1).getGuid() != null);
        
        survey.setIdentifier("newIdentifier");
        surveyDao.updateSurvey(survey);
        survey = surveyDao.getSurvey(survey.getGuid(), survey.getVersionedOn());
        assertEquals("Identifier has been changed", "newIdentifier", survey.getIdentifier());
        
        surveyDao.deleteSurvey(survey.getGuid(), survey.getVersionedOn());
        
        try {
            survey = surveyDao.getSurvey(survey.getGuid(), survey.getVersionedOn());
            fail("Should have thrown an exception");
        } catch(SurveyNotFoundException snfe) {
            assertEquals("Correct survey GUID", survey.getGuid(), snfe.getSurvey().getGuid());
            assertEquals("Correct versionedOn value", survey.getVersionedOn(), snfe.getSurvey().getVersionedOn());
        }
    }

    // UPDATE SURVEY
    
    @Test
    public void canUpdateASurveyVersion() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        
        Survey nextVersion = surveyDao.versionSurvey(survey.getGuid(), survey.getVersionedOn());
        
        // If you change these, it looks like a different survey, you'll just get a not found exception.
        //survey.setGuid("A");
        //survey.setStudyKey("E");
        //survey.setVersionedOn(new DateTime().getMillis());
        survey.setIdentifier("B");
        survey.setName("C");
        
        surveyDao.updateSurvey(survey);
        survey = surveyDao.getSurvey(survey.getGuid(), survey.getVersionedOn());
        
        assertEquals("Identifier can be updated", "B", survey.getIdentifier());
        assertEquals("Name can be updated", "C", survey.getName());
        
        // Now verify the nextVersion has not been changed
        nextVersion = surveyDao.getSurvey(nextVersion.getGuid(), nextVersion.getVersionedOn());
        assertEquals("Next version has same identifier", "overview", nextVersion.getIdentifier());
        assertEquals("Next name has not changed", "Health Overview Test Survey", nextVersion.getName());
    }
    
    @Test
    public void crudSurveyQuestions() {
        Survey survey = constructTestSurvey();
        
        SurveyQuestion question = new DynamoSurveyQuestion();
        question.setIdentifier("age");
        question.setData(JsonNodeFactory.instance.objectNode());
        survey.getQuestions().add(question);
        
        question = new DynamoSurveyQuestion();
        question.setIdentifier("gender");
        survey.getQuestions().add(question);
        
        survey = surveyDao.createSurvey(survey);
        
        // Now, alter these, and verify they are altered
        survey.getQuestions().get(0).setIdentifier("new age");
        survey.getQuestions().remove(1);
        surveyDao.updateSurvey(survey);
        
        survey = surveyDao.getSurvey(survey.getGuid(), survey.getVersionedOn());

        assertEquals("Survey only has one question", 1, survey.getQuestions().size());
        assertEquals("Survey has updated the one question's identifier", "new age", survey.getQuestions().get(0)
                .getIdentifier());
    }
    
    @Test(expected=ConcurrentModificationException.class)
    public void cannotUpdateVersionWithoutException() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        survey.setVersion(44L);
        surveyDao.updateSurvey(survey);
    }
    
    @Test(expected=PublishedSurveyException.class)
    public void cannotUpdatePublishedSurveys() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        survey.setName("This is a new name");
        surveyDao.updateSurvey(survey);
    }
    
    // VERSION SURVEY
    
    @Test
    public void canVersionASurveyEvenIfPublished() {
        Survey survey = constructTestSurvey();
        
        survey = surveyDao.createSurvey(survey);
        surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        Long originalVersion = survey.getVersionedOn();
        survey = surveyDao.versionSurvey(survey.getGuid(), survey.getVersionedOn());
        
        Long newVersion = survey.getVersionedOn();
        assertNotEquals("Versions differ", newVersion, originalVersion);
    }
    
    // PUBLISH SURVEY
    
    @Test
    public void canPublishASurvey() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        survey = surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        assertTrue("Survey is marked published", survey.isPublished());
        
        Survey pubSurvey = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY).get(0);
        
        assertEquals("Same survey GUID", survey.getGuid(), pubSurvey.getGuid());
        assertEquals("Same survey versionedOn", survey.getVersionedOn(), pubSurvey.getVersionedOn());
        assertTrue("Published survey is marked published", pubSurvey.isPublished());
        
        // Publishing again is harmless
        survey = surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        pubSurvey = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY).get(0);
        assertEquals("Same survey GUID", survey.getGuid(), pubSurvey.getGuid());
        assertEquals("Same survey versionedOn", survey.getVersionedOn(), pubSurvey.getVersionedOn());
        assertTrue("Published survey is marked published", pubSurvey.isPublished());
    }
    
    @Test
    public void canPublishANewerVersionOfASurvey() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        survey = surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        Survey laterSurvey = surveyDao.versionSurvey(survey.getGuid(), survey.getVersionedOn());
        assertNotEquals("Surveys do not have the same versionedOn", survey.getVersionedOn(), laterSurvey.getVersionedOn());
        
        laterSurvey = surveyDao.publishSurvey(laterSurvey.getGuid(), laterSurvey.getVersionedOn());
        
        Survey pubSurvey = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY).get(0);
        assertEquals("Later survey is the published survey", laterSurvey.getVersionedOn(), pubSurvey.getVersionedOn());
    }
    
    // GET SURVEYS
    
    @Test
    public void failToGetSurveysByBadStudyKey() {
        List<Survey> surveys = surveyDao.getSurveys("foo");
        assertEquals("No surveys", 0, surveys.size());
    }
    
    @Test
    public void canGetAllSurveys() {
        Survey survey = constructTestSurvey("Test Survey 1");
        surveyDao.createSurvey(survey);
        
        survey = constructTestSurvey("Test Survey 2");
        surveyDao.createSurvey(survey);
        
        survey = constructTestSurvey("Test Survey 3");
        surveyDao.createSurvey(survey);
        
        survey = constructTestSurvey("Test Survey 4");
        surveyDao.createSurvey(survey);
        
        survey = constructTestSurvey("Test Survey 5");
        surveyDao.createSurvey(survey);
        
        surveyDao.versionSurvey(survey.getGuid(), survey.getVersionedOn());
        
        // Get all surveys
        List<Survey> surveys = surveyDao.getSurveys(STUDY_KEY);
        
        assertEquals("All surveys are returned", 6, surveys.size());
        
        // Get all surveys of a version
        surveys = surveyDao.getSurveyVersions(STUDY_KEY, survey.getGuid());
        assertEquals("All surveys are returned", 2, surveys.size());

        Survey survey1 = surveys.get(0);
        Survey survey2 = surveys.get(1);
        assertEquals("Surveys have same GUID", survey1.getGuid(), survey2.getGuid());
        assertEquals("Surveys have same Study key", survey1.getStudyKey(), survey2.getStudyKey());
        assertNotEquals("Surveys have different versionedOn attribute", survey1.getVersionedOn(), survey2.getVersionedOn());
    }
    
    // CLOSE SURVEY
    
    @Test
    public void canClosePublishedSurvey() {
        Survey survey = constructTestSurvey();
        
        survey = surveyDao.createSurvey(survey);
        survey = surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        survey = surveyDao.closeSurvey(survey.getGuid(), survey.getVersionedOn());
        assertEquals("Survey no longer published", false, survey.isPublished());
        
        survey = surveyDao.getSurvey(survey.getGuid(), survey.getVersionedOn());
        assertEquals("Survey no longer published", false, survey.isPublished());
    }
    
    // GET PUBLISHED SURVEY
    
    @Test
    public void canRetrieveMostRecentlyPublishedSurveysWithManyVersions() {
        // Version 1.
        Survey survey1 = constructTestSurvey("Name 1");
        survey1 = surveyDao.createSurvey(survey1);
        
        // Version 2.
        Survey survey2 = surveyDao.versionSurvey(survey1.getGuid(), survey1.getVersionedOn());
        
        // Version 3 (tossed)
        surveyDao.versionSurvey(survey2.getGuid(), survey2.getVersionedOn());
        
        // Publish one version
        surveyDao.publishSurvey(survey1.getGuid(), survey1.getVersionedOn());
        
        List<Survey> surveys = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY);
        assertEquals("Retrieved published survey v1", survey1.getVersionedOn(), surveys.get(0).getVersionedOn());
        
        // Publish a later version
        surveyDao.publishSurvey(survey2.getGuid(), survey2.getVersionedOn());
        
        // Now the most recent version of this survey should be survey2.
        surveys = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY);
        assertEquals("Retrieved published survey v2", survey2.getVersionedOn(), surveys.get(0).getVersionedOn());
    }
    
    @Test
    public void canRetrieveMostRecentPublishedSurveysWithManySurveys() {
        Survey survey1 = constructTestSurvey("Name 1");
        survey1 = surveyDao.createSurvey(survey1);
        surveyDao.publishSurvey(survey1.getGuid(), survey1.getVersionedOn());
        
        Survey survey2 = constructTestSurvey("Name 2");
        survey2 = surveyDao.createSurvey(survey2);
        surveyDao.publishSurvey(survey2.getGuid(), survey2.getVersionedOn());

        Survey survey3 = constructTestSurvey("Name 3");
        survey3 = surveyDao.createSurvey(survey3);
        surveyDao.publishSurvey(survey3.getGuid(), survey3.getVersionedOn());
        
        List<Survey> published = surveyDao.getMostRecentlyPublishedSurveys(STUDY_KEY);
        
        assertEquals("There are three published surveys", 3, published.size());
        assertEquals("The first is survey3", survey3.getGuid(), published.get(0).getGuid());
        assertEquals("The middle is survey2", survey2.getGuid(), published.get(1).getGuid());
        assertEquals("The last is survey1", survey1.getGuid(), published.get(2).getGuid());
    }
    
    // DELETE SURVEY
    
    @Test(expected=PublishedSurveyException.class)
    public void cannotDeleteAPublishedSurvey() {
        Survey survey = constructTestSurvey();
        survey = surveyDao.createSurvey(survey);
        surveyDao.publishSurvey(survey.getGuid(), survey.getVersionedOn());
        
        surveyDao.deleteSurvey(survey.getGuid(), survey.getVersionedOn());
    }
    
    // GET SURVEY
    // * Covered by other tests
    
}
