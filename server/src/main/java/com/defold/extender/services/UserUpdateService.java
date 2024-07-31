package com.defold.extender.services;

import org.springframework.stereotype.Service;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Properties;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Service
public class UserUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserUpdateService.class);

    private InMemoryUserDetailsManager userDetailsManager;

    private Resource usersResource;

    private long updateInterval;

    private long lastUpdateTimestamp = 0;

    public UserUpdateService(
        @Value("${extender.authentication.users}") Resource usersResource,
        @Value("${extender.authentication.update-interval}") long updateInterval,
        InMemoryUserDetailsManager userDetailsManager) {
            LOGGER.info("UserUpdateService() updateInterval = " + updateInterval + " usersResource = " + usersResource);
            this.usersResource = usersResource;
            this.updateInterval = updateInterval;
            this.userDetailsManager = userDetailsManager;
            update();
    }
    /**
     * Load users from the resource specified by extender.authentication.users
     * in application.yml or env variable.
     * This can either be a file or a URI
     * @return Loaded users as a Properties instance
     */
    private Properties loadUsers() {
        Properties users = new Properties();
        try {
            users.load(usersResource.getInputStream());
        }
        catch(IOException e) {
            LOGGER.info("UserUpdateService - Unable to update users. " + e.getMessage());
        }
        return users;
    }

    /**
     * Update users from a Properties instance. The users should be defined as
     * a map of username to user details:
     *
     * username = password,ROLE_ANDROID,ROLE_IOS,ROLE_HTML5,ROLE_WINDOWS,ROLE_LINUX,ROLE_MACOS,ROLE_SWITCH,disabled
     *
     * Refer to README_SECURITY.md for additional information
     */
    private void updateUsers(Properties users) {
        for(String username : users.stringPropertyNames()) {
            List<String> userSettings = Arrays.asList(users.getProperty(username).split(","));
            final String password = userSettings.get(0);
            final boolean disabled = userSettings.get(userSettings.size() - 1).equals("disabled");
            final String[] authorities = userSettings.subList(1, userSettings.size() - 1).toArray(new String[0]);

            // For info about password storage formats: https://spring.io/blog/2017/11/01/spring-security-5-0-0-rc1-released#password-storage-format
            final UserDetails user = User.builder().disabled(disabled).username(username).password("{noop}"+password).authorities(authorities).build();
            if (userDetailsManager.userExists(username)) {
                LOGGER.debug("UserUpdateService - Updating user " + user.toString());
                userDetailsManager.updateUser(user);
            }
            else {
                LOGGER.debug("UserUpdateService - Creating user " + user.toString());
                userDetailsManager.createUser(user);
            }
        }
    }

    /**
     * Update the users from the resource specified by extender.authentication.users
     * The users will never be updated more frequently than specified by the
     * extender.authentication.users configuration value.
     */
    public void update() {
        if (usersResource == null) {
            LOGGER.info("UserUpdateService - No extender.authentication.users configuration has been set");
            return;
        }

        if (userDetailsManager == null) {
            LOGGER.info("UserUpdateService - No userDetailsManager");
            return;
        }

        final long now = System.currentTimeMillis();
        if ((now - lastUpdateTimestamp) < updateInterval) {
            LOGGER.info("UserUpdateService - update() skipping update since not enough time has elapsed");
            return;
        }
        lastUpdateTimestamp = now;

        updateUsers(loadUsers());
    }

    @Scheduled(fixedDelayString="${extender.authentication.update-interval:900000}")
    public void schedulesUpdate() {
        LOGGER.info("Schedule user credentials update");
        update();
    }
}
