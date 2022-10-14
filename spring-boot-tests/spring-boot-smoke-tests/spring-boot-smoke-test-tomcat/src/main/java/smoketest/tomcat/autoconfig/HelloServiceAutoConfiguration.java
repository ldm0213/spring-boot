package smoketest.tomcat.autoconfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import smoketest.tomcat.propeties.HelloServiceProperties;
import smoketest.tomcat.service.HelloWorldService;

/**
 * @author lidongmeng
 * Created on 2022-10-14
 */
@Configuration
@EnableConfigurationProperties(HelloServiceProperties.class)
@ConditionalOnClass(HelloWorldService.class)
@ConditionalOnProperty(prefix = "hello", value = "enable", matchIfMissing = true)
public class HelloServiceAutoConfiguration {
	@Autowired
	private HelloServiceProperties helloServiceProperties;

	@Bean
	public HelloWorldService helloService() {
		HelloWorldService helloWorldService = new HelloWorldService();
		helloWorldService.setMsg(helloServiceProperties.getMsg());
		return helloWorldService;
	}
}
