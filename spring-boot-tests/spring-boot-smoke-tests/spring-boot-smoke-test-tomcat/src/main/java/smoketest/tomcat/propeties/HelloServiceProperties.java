package smoketest.tomcat.propeties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author lidongmeng
 * Created on 2022-10-14
 */
@ConfigurationProperties(prefix = "hello")
public class HelloServiceProperties {
	private static final String MSG = "world";
	private String msg = MSG;

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}
}