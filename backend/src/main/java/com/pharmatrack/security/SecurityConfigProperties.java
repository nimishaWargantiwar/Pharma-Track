package com.pharmatrack.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for CORS, bootstrap admin provisioning, and optional local demo users.
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityConfigProperties {

    private Cors cors = new Cors();
    private Bootstrap bootstrap = new Bootstrap();
    private LocalDemo localDemo = new LocalDemo();

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public LocalDemo getLocalDemo() {
        return localDemo;
    }

    public void setLocalDemo(LocalDemo localDemo) {
        this.localDemo = localDemo;
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Bootstrap {
        private boolean enabled = true;
        private String adminUsername;
        private String adminPassword;
        private String adminFullName = "System Administrator";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getAdminFullName() {
            return adminFullName;
        }

        public void setAdminFullName(String adminFullName) {
            this.adminFullName = adminFullName;
        }
    }

    public static class LocalDemo {
        private boolean enabled = false;
        private String password;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}

