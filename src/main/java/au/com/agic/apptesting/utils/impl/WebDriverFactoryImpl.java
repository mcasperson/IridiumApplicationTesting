package au.com.agic.apptesting.utils.impl;

import au.com.agic.apptesting.constants.Constants;
import au.com.agic.apptesting.utils.ProxyDetails;
import au.com.agic.apptesting.utils.SystemPropertyUtils;
import au.com.agic.apptesting.utils.WebDriverFactory;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.opera.OperaDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.safari.SafariDriver;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static au.com.agic.apptesting.constants.Constants.PHANTOMJS_LOGGING_LEVEL_SYSTEM_PROPERTY;

/**
 * An implementation of the web driver factory
 */
public class WebDriverFactoryImpl implements WebDriverFactory {
	private static final int PHANTOM_JS_SCREEN_WIDTH = 1280;
	private static final int PHANTOM_JS_SCREEN_HEIGHT = 1024;
	private static final int PHNATOMJS_TIMEOUTS = 30;
	private static final SystemPropertyUtils SYSTEM_PROPERTY_UTILS = new SystemPropertyUtilsImpl();

	@Override
	public WebDriver createWebDriver(final List<ProxyDetails<?>> proxies) {
		final String browser = SYSTEM_PROPERTY_UTILS.getProperty(
			Constants.TEST_DESTINATION_SYSTEM_PROPERTY);

		/*
			Configure the proxy settings
		 */
		final DesiredCapabilities capabilities = DesiredCapabilities.htmlUnitWithJs();

		final Optional<ProxyDetails<?>> mainProxy = proxies.stream()
			.filter(ProxyDetails::isMainProxy)
			.findFirst();

		if (mainProxy.isPresent()) {
			Proxy proxy = new Proxy();
			proxy.setHttpProxy("localhost:" + mainProxy.get().getPort());
			capabilities.setCapability("proxy", proxy);
		}

		if (Constants.FIREFOX.equalsIgnoreCase(browser)) {
			final String firefoxProfile = SYSTEM_PROPERTY_UTILS.getProperty(
				Constants.FIREFOX_PROFILE_SYSTEM_PROPERTY);

			/*
				If we have not specified a profile via the system properties, go ahead
				and create one here.
			 */
			if (StringUtils.isBlank(firefoxProfile)) {
				final FirefoxProfile profile = new FirefoxProfile();

				/*
					Set the proxy
				 */
				if (mainProxy.isPresent()) {

					profile.setPreference("network.proxy.type", 1);
					profile.setPreference("network.proxy.http", "localhost");
					profile.setPreference("network.proxy.http_port", mainProxy.get().getPort());
					profile.setPreference("network.proxy.ssl", "localhost");
					profile.setPreference("network.proxy.ssl_port", mainProxy.get().getPort());
					profile.setPreference("network.proxy.no_proxies_on", "");
				}

				return new FirefoxDriver(profile);
			}

			return new FirefoxDriver(capabilities);

		}

		if (Constants.SAFARI.equalsIgnoreCase(browser)) {
			return new SafariDriver(capabilities);
		}

		if (Constants.OPERA.equalsIgnoreCase(browser)) {
			return new OperaDriver(capabilities);
		}

		if (Constants.IE.equalsIgnoreCase(browser)) {
			return new InternetExplorerDriver(capabilities);
		}

		if (Constants.EDGE.equalsIgnoreCase(browser)) {
			return new EdgeDriver(capabilities);
		}

		if (Constants.PHANTOMJS.equalsIgnoreCase(browser)) {

			/*
				PhantomJS will often report a lot of unnecessary errors, so by default
				we turn logging off. But you can override this behaviour with a
				system property.
			 */
			final String loggingLevel = StringUtils.defaultIfBlank(
				SYSTEM_PROPERTY_UTILS.getProperty(PHANTOMJS_LOGGING_LEVEL_SYSTEM_PROPERTY),
				Constants.DEFAULT_PHANTOM_JS_LOGGING_LEVEL
			);

			/*
				We need to ignore ssl errors
				https://vaadin.com/forum#!/thread/9200596
			 */
			final String[] cliArgs = {
				"--ignore-ssl-errors=true",
				"--webdriver-loglevel=" + loggingLevel};
			capabilities.setCapability(PhantomJSDriverService.PHANTOMJS_CLI_ARGS, cliArgs);

			final PhantomJSDriver retValue = new PhantomJSDriver(capabilities);

			/*
				This is required by PhantomJS
				https://github.com/angular/protractor/issues/585
			 */
			retValue.manage().window().setSize(
				new Dimension(PHANTOM_JS_SCREEN_WIDTH, PHANTOM_JS_SCREEN_HEIGHT));

			/*
				Give the dev servers a large timeout
			 */
			retValue.manage().timeouts()
				.pageLoadTimeout(PHNATOMJS_TIMEOUTS, TimeUnit.SECONDS)
				.implicitlyWait(PHNATOMJS_TIMEOUTS, TimeUnit.SECONDS);

			return retValue;
		}

		return new ChromeDriver(capabilities);
	}
}