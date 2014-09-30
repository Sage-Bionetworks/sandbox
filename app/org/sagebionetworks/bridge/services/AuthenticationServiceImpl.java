package org.sagebionetworks.bridge.services;

import java.util.UUID;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.crypto.BridgeEncryptor;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.Email;
import org.sagebionetworks.bridge.models.EmailVerification;
import org.sagebionetworks.bridge.models.PasswordReset;
import org.sagebionetworks.bridge.models.SignIn;
import org.sagebionetworks.bridge.models.SignUp;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.User;
import org.sagebionetworks.bridge.models.UserSession;
import org.sagebionetworks.bridge.stormpath.StormpathFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.mvc.Http;

import com.stormpath.sdk.account.Account;
import com.stormpath.sdk.application.Application;
import com.stormpath.sdk.authc.AuthenticationRequest;
import com.stormpath.sdk.authc.UsernamePasswordRequest;
import com.stormpath.sdk.client.Client;
import com.stormpath.sdk.directory.CustomData;
import com.stormpath.sdk.directory.Directory;
import com.stormpath.sdk.resource.ResourceException;

public class AuthenticationServiceImpl implements AuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(AuthenticationServiceImpl.class);

    private Client stormpathClient;
    private CacheProvider cacheProvider;
    private BridgeConfig config;
    private BridgeEncryptor healthCodeEncryptor;
    private HealthCodeService healthCodeService;
    private EmailValidator emailValidator = EmailValidator.getInstance();

    public void setStormpathClient(Client client) {
        this.stormpathClient = client;
    }

    public void setCacheProvider(CacheProvider cache) {
        this.cacheProvider = cache;
    }

    public void setBridgeConfig(BridgeConfig config) {
        this.config = config;
    }

    public void setHealthCodeEncryptor(BridgeEncryptor encryptor) {
        this.healthCodeEncryptor = encryptor;
    }

    public void setHealthCodeService(HealthCodeService healthCodeService) {
        this.healthCodeService = healthCodeService;
    }
    
    @Override
    public UserSession getSession(String sessionToken) {
        if (sessionToken == null) {
            return null;
        }
        return cacheProvider.getUserSession(sessionToken);
    }

    @Override
    public UserSession signIn(Study study, SignIn signIn) throws ConsentRequiredException, EntityNotFoundException,
            BridgeServiceException {

        final long start = System.nanoTime();

        if (signIn == null) {
            throw new BridgeServiceException("SignIn object is required", Http.Status.NOT_FOUND);
        } else if (StringUtils.isBlank(signIn.getUsername())) {
            throw new BridgeServiceException("Username/email must not be null", Http.Status.NOT_FOUND);
        } else if (StringUtils.isBlank(signIn.getPassword())) {
            throw new BridgeServiceException("Password must not be null", Http.Status.NOT_FOUND);
        } else if (study == null) {
            throw new BridgeServiceException("Study is required", HttpStatus.SC_BAD_REQUEST);
        }

        AuthenticationRequest<?, ?> request = null;
        UserSession session = null;
        try {
            Application application = StormpathFactory.createStormpathApplication(stormpathClient);
            logger.info("sign in create app " + (System.nanoTime() - start) );
            request = new UsernamePasswordRequest(signIn.getUsername(), signIn.getPassword());
            Account account = application.authenticateAccount(request).getAccount();
            logger.info("sign in authenticate " + (System.nanoTime() - start));
            session = createSessionFromAccount(study, account);
            cacheProvider.setUserSession(session.getSessionToken(), session);
            
            if (!session.getUser().doesConsent()) {
                throw new ConsentRequiredException(session);
            }

        } catch (ResourceException re) {
            throw new EntityNotFoundException(User.class, re.getDeveloperMessage());
        } finally {
            if (request != null) {
                request.clear();
            }
        }

        final long end = System.nanoTime();
        logger.info("sign in service " + (end - start));

        return session;
    }

    @Override
    public void signOut(String sessionToken) {
        if (sessionToken != null) {
            cacheProvider.remove(sessionToken);
        }
    }

    @Override
    public void signUp(SignUp signUp, Study study) throws BridgeServiceException {
        if (study == null) {
            throw new BridgeServiceException("Study object is required", HttpStatus.SC_BAD_REQUEST);
        } else if (StringUtils.isBlank(study.getStormpathDirectoryHref())) {
            throw new BridgeServiceException("Study's StormPath directory HREF is required", HttpStatus.SC_BAD_REQUEST);
        } else if (signUp == null) {
            throw new BridgeServiceException("SignUp object is required", HttpStatus.SC_BAD_REQUEST);
        } else if (StringUtils.isBlank(signUp.getEmail())) {
            throw new BridgeServiceException("Email is required", HttpStatus.SC_BAD_REQUEST);
        } else if (StringUtils.isBlank(signUp.getPassword())) {
            throw new BridgeServiceException("Password is required", HttpStatus.SC_BAD_REQUEST);
        } else if (!emailValidator.isValid(signUp.getEmail())) {
            throw new BridgeServiceException("Email address does not appear to be valid", HttpStatus.SC_BAD_REQUEST);
        }
        try {
            Directory directory = stormpathClient.getResource(study.getStormpathDirectoryHref(), Directory.class);
            
            Account account = stormpathClient.instantiate(Account.class);
            account.setGivenName("<EMPTY>");
            account.setSurname("<EMPTY>");
            account.setEmail(signUp.getEmail());
            account.setUsername(signUp.getUsername());
            account.setPassword(signUp.getPassword());
            directory.createAccount(account);
        } catch (ResourceException re) {
            throw new BridgeServiceException(re.getDeveloperMessage(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Override
    public UserSession verifyEmail(Study study, EmailVerification verification) throws BridgeServiceException,
            ConsentRequiredException {
        if (verification == null) {
            throw new BridgeServiceException("Verification object is required", HttpStatus.SC_BAD_REQUEST);
        } else if (verification.getSptoken() == null) {
            throw new BridgeServiceException("Email verification token is required", HttpStatus.SC_BAD_REQUEST);
        }
        UserSession session = null;
        try {
            Account account = stormpathClient.getCurrentTenant().verifyAccountEmail(verification.getSptoken());
            
            session = createSessionFromAccount(study, account);
            cacheProvider.setUserSession(session.getSessionToken(), session);
            
            if (!session.getUser().doesConsent()) {
                throw new ConsentRequiredException(session);
            }
            return session;
        } catch (ResourceException re) {
            throw new BridgeServiceException(re.getDeveloperMessage(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Override
    public void requestResetPassword(Email email) throws BridgeServiceException {
        if (email == null) {
            throw new BridgeServiceException("Email object is required", HttpStatus.SC_BAD_REQUEST);
        }
        if (StringUtils.isBlank(email.getEmail())) {
            throw new BridgeServiceException("Email is required", HttpStatus.SC_BAD_REQUEST);
        }
        try {
            Application application = StormpathFactory.createStormpathApplication(stormpathClient);
            application.sendPasswordResetEmail(email.getEmail());
        } catch (ResourceException re) {
            throw new BridgeServiceException(re.getDeveloperMessage(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Override
    public void resetPassword(PasswordReset passwordReset) throws BridgeServiceException {
        if (passwordReset == null) {
            throw new BridgeServiceException("Password reset object is required", HttpStatus.SC_BAD_REQUEST);
        } else if (StringUtils.isBlank(passwordReset.getSptoken())) {
            throw new BridgeServiceException("Password reset token is required", HttpStatus.SC_BAD_REQUEST);
        } else if (StringUtils.isBlank(passwordReset.getPassword())) {
            throw new BridgeServiceException("Password is required", HttpStatus.SC_BAD_REQUEST);
        }
        try {
            Application application = StormpathFactory.createStormpathApplication(stormpathClient);
            Account account = application.verifyPasswordResetToken(passwordReset.getSptoken());
            account.setPassword(passwordReset.getPassword());
            account.save();
        } catch (ResourceException e) {
            throw new BridgeServiceException(e.getDeveloperMessage(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    private UserSession createSessionFromAccount(Study study, Account account) {
        UserSession session;
        session = new UserSession();
        session.setAuthenticated(true);
        session.setEnvironment(config.getEnvironment().getEnvName());
        session.setSessionToken(UUID.randomUUID().toString());
        User user = new User(account);
        user.setStudyKey(study.getKey());

        CustomData data = account.getCustomData();
        String consentKey = study.getKey()+BridgeConstants.CUSTOM_DATA_CONSENT_SUFFIX;
        // TODO: Read from ConsentService
        user.setConsent( "true".equals(data.get(consentKey)) );
        
        // New users will not yet have consented and generated a health ID, so skip this if it doesn't exist.
        if (user.isConsent()) {
            final String hdcKey = study.getKey()+BridgeConstants.CUSTOM_DATA_HEALTH_CODE_SUFFIX;
            final String encryptedId = (String)data.get(hdcKey);
            String healthId = healthCodeEncryptor.decrypt(encryptedId);
            String healthCode = healthCodeService.getHealthCode(healthId);
            user.setHealthDataCode(healthCode);
        }        
        // Generating the health data key has to be done regardless of consent, because we can't 
        // check consent until we have a health data code
        /*
        CustomData data = account.getCustomData();
        final String hdcKey = study.getKey()+BridgeConstants.CUSTOM_DATA_HEALTH_CODE_SUFFIX;
        final String encryptedId = (String)data.get(hdcKey);
        
        if (encryptedId != null) {
            String healthId = healthCodeEncryptor.decrypt(encryptedId);
            logger.info("The healthId: " + healthId);
            String healthDataCode = healthCodeService.getHealthCode(healthId);
            logger.info("The health data code: " + healthDataCode);
            user.setHealthDataCode(healthDataCode);
            boolean hasConsented = consentService.hasUserConsentedToResearch(user, study);
            user.setConsent(hasConsented);
        }
        */
        session.setUser(user);
        return session;
    }
}
