package ai.nubase.ai.gateway.billing;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingFilterConfiguration {

    /** Billing admission is positioned explicitly inside Spring Security after gateway-key auth. */
    @Bean
    public FilterRegistrationBean<BillingAdmissionFilter> billingAdmissionServletRegistration(
            BillingAdmissionFilter filter) {
        FilterRegistrationBean<BillingAdmissionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
