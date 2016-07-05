package au.com.agic.apptesting.steps;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

import au.com.agic.apptesting.State;
import au.com.agic.apptesting.constants.Constants;
import au.com.agic.apptesting.exception.InvalidInputException;
import au.com.agic.apptesting.utils.BrowserInteropUtils;
import au.com.agic.apptesting.utils.ProxyDetails;
import au.com.agic.apptesting.utils.SystemPropertyUtils;
import au.com.agic.apptesting.utils.ThreadDetails;
import au.com.agic.apptesting.utils.impl.BrowserInteropUtilsImpl;
import au.com.agic.apptesting.utils.impl.BrowsermobProxyUtilsImpl;
import au.com.agic.apptesting.utils.impl.SystemPropertyUtilsImpl;

import net.lightbody.bmp.BrowserMobProxy;
import net.lightbody.bmp.filters.RequestFilter;
import net.lightbody.bmp.util.HttpMessageContents;
import net.lightbody.bmp.util.HttpMessageInfo;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.security.UserAndPassword;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cucumber.api.Scenario;
import cucumber.api.java.After;
import cucumber.api.java.Before;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Implementations of the Cucumber steps.
 */
public class StepDefinitions {

	private static final Logger LOGGER = LoggerFactory.getLogger(StepDefinitions.class);

	private static final SystemPropertyUtils SYSTEM_PROPERTY_UTILS = new SystemPropertyUtilsImpl();
	private static final BrowserInteropUtils BROWSER_INTEROP_UTILS = new BrowserInteropUtilsImpl();

	private static final String SCREENSHOT_DATE_FORMAT = "YYYYMMddHHmmssSSS";

	private static final Pattern BLANK_OR_MASKED_RE = Pattern.compile("^(_|\\s)+$");
	private static final Pattern SINGLE_QUOTE_RE = Pattern.compile("'");

	private static final int MILLISECONDS_PER_SECOND = 1000;

	/**
	 * How long to delay when entering each character into a text box
	 */
	private static final int KEY_STROKE_DELAY = 300;

	/**
	 * How long to wait for an element to be available
	 */
	private static final int WAIT = 2;
	/**
	 * How long to sleep between each step Each new Scenario appears to create a new instance of this class, so we need
	 * to make this value static so when it is sent once it carries across
	 */
	private static Integer defaultWait = 0;
	/**
	 * Get the web driver for this thread
	 */
	private final ThreadDetails threadDetails =
		State.THREAD_DESIRED_CAPABILITY_MAP.getDesiredCapabilitiesForThread(
			Thread.currentThread().getName());

	// <editor-fold desc="Events">

	/**
	 * If any scenario failed, we throw an exception which prevents the new scenario from loading. This prevents a
	 * situation where the test script continues to run after some earlier failure, which doesn't make sense in end to
	 * end tests.
	 */
	@Before
	public void setup() {
		if (threadDetails.getFailed()) {
			throw new IllegalStateException("Previous scenario failed!");
		}
	}

	/**
	 * If this scenario failed, note this in the thread details so subsequent scenarios are not run
	 *
	 * @param scenario The cucumber scenario
	 */
	@After
	public void teardown(final Scenario scenario) {
		if (!threadDetails.getFailed()) {
			takeScreenshot(" " + scenario.getName());
		}

		threadDetails.setFailed(scenario.isFailed());
	}

	private void takeScreenshot(final String suffix) {
		/*
			Take a screenshot if we have enabled the setting
		 */
		final boolean enabledScreenshots = Boolean.parseBoolean(
			SYSTEM_PROPERTY_UTILS.getProperty(Constants.ENABLE_SCREENSHOTS));

		try {
			if (enabledScreenshots) {
				if (threadDetails.getWebDriver() instanceof TakesScreenshot) {
					final File screenshot =
						((TakesScreenshot) threadDetails.getWebDriver())
							.getScreenshotAs(OutputType.FILE);

					/*
						Screenshot filenames are the time that it was taken to allow for easy
						sorting.
					 */
					final String filename = new SimpleDateFormat(SCREENSHOT_DATE_FORMAT)
						.format(new Date()) + suffix + ".png";

					final File reportFile =
						new File(threadDetails.getReportDirectory() + "/" + filename);

					FileUtils.copyFile(screenshot, reportFile);
					LOGGER.info("Saved screenshot to {}", reportFile.getAbsolutePath());
				}
			}
		} catch (final IOException ex) {
			LOGGER.error("There was an error saving or copying the screenshot.", ex);
		}
	}

	// <editor-fold desc="Debugging">

	/**
	 * Block access to all urls that match the regex
	 * @param url A regular expression that matches URLs to be blocked
	 * @param response The response code to send back when a matching URL is accessed
	 */
	@When("^I block access to the URL regex \"(.*?)\" with response \"(\\d+)\"$")
	public void blockUrl(final String url, final Integer response) {
		final Optional<ProxyDetails<?>> proxy =
			threadDetails.getProxyInterface(BrowsermobProxyUtilsImpl.PROXY_NAME);
		if (proxy.isPresent()) {
			final BrowserMobProxy browserMobProxy = (BrowserMobProxy) proxy.get().getInterface().get();
			browserMobProxy.blacklistRequests(url, response);
		}
	}

	/**
	 * Block access to all urls that match the regex
	 * @param url A regular expression that matches URLs to be blocked
	 * @param response The response code to send back when a matching URL is accessed
	 * @param type The http type of request to block (CONNECT, GET, PUT etc)
	 */
	@When("^I block access to the URL regex \"(.*?)\" of the type \"(.*?)\" with response \"(\\d+)\"$")
	public void blockUrl(final String url, final String type, final Integer response) {
		final Optional<ProxyDetails<?>> proxy =
			threadDetails.getProxyInterface(BrowsermobProxyUtilsImpl.PROXY_NAME);
		if (proxy.isPresent()) {
			final BrowserMobProxy browserMobProxy = (BrowserMobProxy) proxy.get().getInterface().get();
			browserMobProxy.blacklistRequests(url, response, type);
		}
	}

	/**
	 * Apps like life express will often include AWSELB cookies from both the root "/" context and the application
	 * "/life-express" context. Supplying both cookies means that requests are sent to a EC2 instance that didn't
	 * generate the initial session, and so the request fails. This step allows us to remove these duplicated cookies
	 * from the request.
	 *
	 * @param url The regex that matches URLs that should have duplicate AWSELB cookies removed
	 */
	@When("^I remove root AWSELB cookie from the request to the URL regex \"(.*?)\"$")
	public void stripHeaders(final String url) {
		final Optional<ProxyDetails<?>> proxy =
			threadDetails.getProxyInterface(BrowsermobProxyUtilsImpl.PROXY_NAME);
		if (proxy.isPresent()) {
			final BrowserMobProxy browserMobProxy = (BrowserMobProxy) proxy.get().getInterface().get();
			browserMobProxy.addRequestFilter(new RequestFilter() {
				@Override
				public HttpResponse filterRequest(
					final HttpRequest request,
					final HttpMessageContents contents,
					final HttpMessageInfo messageInfo) {
					if (messageInfo.getOriginalRequest().getUri().matches(url)) {
						final Optional<String> cookies =
							Optional.ofNullable(request.headers().get("Cookie"));

						/*
							Only proceed if we have supplied some cookies
						 */
						if (cookies.isPresent()) {
							/*
								Find the root context cookie
							 */
							final Optional<Cookie> awselb = threadDetails.getWebDriver().manage().getCookies().stream()
								.filter(x -> "AWSELB".equals(x.getName()))
								.filter(x -> "/".equals(x.getPath()))
								.findFirst();

							/*
								If we have a root context cookie, remove it from the request
							 */
							if (awselb.isPresent()) {

								LOGGER.info("WEBAPPTESTER-INFO-0002: Removing AWSELB cookie with value {}", awselb.get().getValue());

								final String newCookie =
									cookies.get().replaceAll(awselb.get().getName() + "=" + awselb.get().getValue()
										+ ";( GMT=; \\d+-\\w+-\\d+=\\d+:\\d+:\\d+;)?", "");

								request.headers().set("Cookie", newCookie);
							}

							final int awsElbCookieCount = StringUtils.countMatches(request.headers().get("Cookie"), "AWSELB");
							if (awsElbCookieCount != 1) {
								LOGGER.info("WEBAPPTESTER-INFO-0003: {} AWSELB cookies found", awsElbCookieCount);
							}
						}

					}

					return null;
				}
			});
		}
	}

	/**
	 * Manually save a screenshot
	 * @param filename The optional filename to use for the screenshot
	 */
	@When("^I take a screenshot(?:(?: called)? \"(.*?)\")?$")
	public void takeScreenshotStep(final String filename) {
		takeScreenshot(StringUtils.defaultIfBlank(filename, ""));
	}

	/**
	 * Dumps the value of a cookie to the logger
	 *
	 * @param cookieName The name of the cookie to dump
	 */
	@When("^I dump the value of the cookie called \"(.*?)\"$")
	public void dumpCookieName(final String cookieName) {
		threadDetails.getWebDriver().manage().getCookies().stream()
			.filter(e -> StringUtils.equals(cookieName, e.getName()))
			.forEach(e -> LOGGER.info("Dumping cookie {}", e));
	}

	/**
	 * Deletes a cookie with the name and path
	 *
	 * @param cookieName The name of the cookie to delete
	 * @param path       The optional path of the cookie to delete. If omitted, all cookies with the cookieName are
	 *                   deleted.
	 */
	@When("^I delete cookies called \"(.*?)\"(?: with the path \"(.*?)\")?$")
	public void deleteCookie(final String cookieName, final String path) {
		final List<Cookie> deleteCookies = threadDetails.getWebDriver().manage().getCookies().stream()
			.filter(e -> StringUtils.equals(cookieName, e.getName()))
			.filter(e -> StringUtils.isBlank(path) || StringUtils.equals(path, e.getPath()))
			.collect(Collectors.toList());

		deleteCookies.stream()
			.forEach(e -> {
				LOGGER.info("Removing cookie {}", e);
				threadDetails.getWebDriver().manage().deleteCookie(e);
			});
	}

	/**
	 * Deletes all cookies
	 */
	@When("^I delete all cookies$")
	public void deleteAllCookie() {
		threadDetails.getWebDriver().manage().deleteAllCookies();
	}

	// </editor-fold>

	// <editor-fold desc="Initialisation">

	/**
	 * This step can be used to define the amount of time each additional step will wait before continuing. This is
	 * useful for web applications that pop new elements into the page in response to user interaction, as there can be
	 * a delay before those elements are available. <p> Set this to 0 to make each step execute immediately after the
	 * last one.
	 *
	 * @param numberOfSeconds The number of seconds to wait before each step completes
	 */
	@When("^I set the default wait time between steps to \"(\\d+)\"(?: seconds)?$")
	public void setDefaultWaitTime(final String numberOfSeconds) {
		defaultWait = Integer.parseInt(numberOfSeconds) * MILLISECONDS_PER_SECOND;
	}

	// </editor-fold>

	// <editor-fold desc="Open Page">

	/**
	 * Takes a gerkin table and saves the key value pairs (key being alias names referenced in other steps).
	 *
	 * @param aliasTable The key value pairs
	 */
	@Given("^the alias mappings")
	public void pageObjectMappings(final Map<String, String> aliasTable) {
		final Map<String, String> dataset = threadDetails.getDataSet();
		dataset.putAll(aliasTable);
		threadDetails.setDataSet(dataset);
	}

	// </editor-fold>

	// <editor-fold desc="Open Page">

	/**
	 * Opens up the supplied URL.
	 *
	 * @param alias include this text if the url is actually an alias to be loaded from the configuration file
	 * @param url The URL of the page to open
	 */
	@When("^I open the page( alias)? \"([^\"]*)\"$")
	public void openPage(final String alias, final String url) {
		threadDetails.getWebDriver().get(" alias".equals(alias) ? threadDetails.getDataSet().get(url) : url);
		sleep(defaultWait);
	}

	/**
	 * Opens up the application with the URL that is mapped to the app attribute in the {@code <feature>} element in the
	 * profile holding the test script. <p> This is different to the "{@code I open the page <url>}" step in that the
	 * URL that is actually used comes from a list maintained in the WebAppTesting-Capabilities profile. This means that
	 * the same script can be run multiple times against different URLs. This is usually used when you want to test
	 * multiple brands, or multiple feature branches.
	 *
	 * @param urlName The URL name from mappings to load.
	 */
	@When("^I open the application(?: \"([^\"]*)\")?$")
	public void openApplication(final String urlName) {

		if (StringUtils.isNotBlank(urlName)) {
			LOGGER.info("WEBAPPTESTER-INFO-0001: Opened the url {}",
				threadDetails.getUrlDetails().getUrl(urlName));

			final String url = threadDetails.getUrlDetails().getUrl(urlName);

			checkState(StringUtils.isNotBlank(url), "The url associated with the app name "
				+ urlName + " was not found. "
				+ "This may mean that you have defined a default URL with the appURLOverride "
				+ "system property. "
				+ "When you set the appURLOverride system property, you can no longer reference "
				+ "named applications. "
				+ "Alternatively, make sure the configuration file defines the named application.");

			threadDetails.getWebDriver().get(url);
		} else {
			LOGGER.info("WEBAPPTESTER-INFO-0001: Opened the url {}",
				threadDetails.getUrlDetails().getDefaultUrl());
			threadDetails.getWebDriver().get(threadDetails.getUrlDetails().getDefaultUrl());
		}

		sleep(defaultWait);
	}

	// </editor-fold>

	// <editor-fold desc="Wait">

	/**
	 * Pauses the execution of the test script for the given number of seconds
	 *
	 * @param sleepDuration The number of seconds to pause the script for
	 */
	@When("^I (?:wait|sleep) for \"(\\d+)\" second(?:s?)$")
	public void sleepStep(final String sleepDuration) {
		sleep(Integer.parseInt(sleepDuration) * MILLISECONDS_PER_SECOND);
	}

	/**
	 * Waits the given amount of time for an element to be displayed (i.e. to be visible) on the page. <p> This is most
	 * useful when waiting for a page to load completely. You can use this step to pause the script until some known
	 * element is visible, which is a good indication that the page has loaded completely.
	 *
	 * @param waitDuration    The maximum amount of time to wait for
	 * @param selector        Either ID, class, xpath, name or css selector
	 * @param alias           If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue   The value used in conjunction with the selector to match the element. If alias was set,
	 *                        this value is found from the data set. Otherwise it is a literal value.
	 * @param ignoringTimeout include this text to continue the script in the event that the element can't be found
	 */
	@When("^I wait \"(\\d+)\" seconds for (?:a|an|the) element with "
		+ "(?:a|an|the) (ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\" to be displayed"
		+ "(,? ignoring timeouts?)?")
	public void displayWaitStep(
		final String waitDuration,
		final String selector,
		final String alias,
		final String selectorValue,
		final String ignoringTimeout) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(
			threadDetails.getWebDriver(), Integer.parseInt(waitDuration));

		try {
			wait.until(ExpectedConditions.visibilityOfElementLocated(by));
		} catch (final TimeoutException ex) {
			/*
				Rethrow if we have not ignored errors
			 */
			if (StringUtils.isBlank(ignoringTimeout)) {
				throw ex;
			}
		}
	}

	/**
	 * Waits the given amount of time for an element to be placed in the DOM. Note that the element does not have to be
	 * visible, just present in the HTML. <p> This is most useful when waiting for a page to load completely. You can
	 * use this step to pause the script until some known element is visible, which is a good indication that the page
	 * has loaded completely.
	 *
	 * @param waitDuration  The maximum amount of time to wait for
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param ignoringTimeout Include this text to ignore a timeout while waiting for the element to be present
	 */
	@When("^I wait \"(\\d+)\" seconds for (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\" "
		+ "to be present(,? ignoring timeouts?)?")
	public void presentWaitStep(
		final String waitDuration,
		final String selector,
		final String alias,
		final String selectorValue,
		final String ignoringTimeout) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(
			threadDetails.getWebDriver(), Integer.parseInt(waitDuration));
		try {
			wait.until(ExpectedConditions.presenceOfElementLocated(by));
		} catch (final TimeoutException ex) {
			/*
				Rethrow if we have not ignored errors
			 */
			if (StringUtils.isBlank(ignoringTimeout)) {
				throw ex;
			}
		}
	}

	/**
	 * Waits the given amount of time for a link with the supplied text to be placed in the DOM. Note that the element
	 * does not have to be visible just present in the HTML.
	 *
	 * @param waitDuration The maximum amount of time to wait for
	 * @param linkContent  The text content of the link we are wait for
	 */
	@When("^I wait \"(\\d+)\" seconds for a link with the text content of \"([^\"]*)\" to be present")
	public void presentLinkStep(final String waitDuration, final String linkContent) {
		final WebDriverWait wait = new WebDriverWait(
			threadDetails.getWebDriver(), Integer.parseInt(waitDuration));
		wait.until(ExpectedConditions.presenceOfElementLocated(By.linkText(linkContent)));
	}

	/**
	 * Waits the given amount of time for an element with the supplied attribute and attribute value to be displayed
	 * (i.e. to be visible) on the page.
	 *
	 * @param waitDuration  The maximum amount of time to wait for
	 * @param attribute     The attribute to use to select the element with
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param ignoringTimeout Include this text to ignore a timeout while waiting for the element to be present
	 */
	@When("^I wait \"(\\d+)\" seconds for (?:a|an|the) element with (?:a|an|the) attribute of \"([^\"]*)\" "
		+ "equal to( alias)? \"([^\"]*)\" to be displayed(,? ignoring timeouts?)?")
	public void displayAttrWait(
		final String waitDuration,
		final String attribute,
		final String alias,
		final String selectorValue,
		final String ignoringTimeout) {
		final String attributeValue = " alias".equals(alias)
			? threadDetails.getDataSet().get(selectorValue) : selectorValue;

		checkState(attributeValue != null, "the aliased attribute value does not exist");

		try {
			final WebDriverWait wait = new WebDriverWait(
				threadDetails.getWebDriver(), Integer.parseInt(waitDuration));
			wait.until(ExpectedConditions.visibilityOfElementLocated(
				By.cssSelector("[" + attribute + "='" + attributeValue + "']")));
		} catch (final TimeoutException ex) {
			/*
				Rethrow if we have not ignored errors
			 */
			if (StringUtils.isBlank(ignoringTimeout)) {
				throw ex;
			}
		}
	}

	/**
	 * Waits the given amount of time for an element with the supplied attribute and attribute value to be displayed
	 * (i.e. to be visible) on the page.
	 *
	 * @param waitDuration  The maximum amount of time to wait for
	 * @param attribute     The attribute to use to select the element with
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param ignoringTimeout Include this text to ignore a timeout while waiting for the element to be present
	 */
	@When("^I wait \"(\\d+)\" seconds for (?:a|an|the) element with (?:a|an|the) attribute of \"([^\"]*)\" "
		+ "equal to( alias)? \"([^\"]*)\" to be present(,? ignoring timeouts?)?")
	public void presentAttrWait(
		final String waitDuration,
		final String attribute,
		final String alias,
		final String selectorValue,
		final String ignoringTimeout) {
		final String attributeValue = " alias".equals(alias)
			? threadDetails.getDataSet().get(selectorValue) : selectorValue;

		checkState(attributeValue != null, "the aliased attribute value does not exist");

		try {
			final WebDriverWait wait = new WebDriverWait(
				threadDetails.getWebDriver(), Integer.parseInt(waitDuration));
			wait.until(ExpectedConditions.presenceOfElementLocated(
				By.cssSelector("[" + attribute + "='" + attributeValue + "']")));
		} catch (final TimeoutException ex) {
			/*
				Rethrow if we have not ignored errors
			 */
			if (StringUtils.isBlank(ignoringTimeout)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Save Field">

	/**
	 * Saves the text value of an element against an alias. Retrieves the "value" attribute content
	 *
	 * @param selector         Either ID, class, xpath, name or css selector
	 * @param alias            If this word is found in the step, it means the selectorValue is found from the data
	 *                         set.
	 * @param selectorValue    The value used in conjunction with the selector to match the element. If alias was set, '
	 *                         this value is found from the data set. Otherwise it is a literal value.
	 * @param destinationAlias The name of the alias to save the text content against
	 * @param exists           If this text is set, an error that would be thrown because the element was not found is
	 *                         ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I save the value of (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\" to the alias \"([^\"]*)\"( if it exists)?")
	public void saveValueAttribute(
		final String selector,
		final String alias,
		final String selectorValue,
		final String destinationAlias,
		final String exists) {
		saveAttributeContent("value", selector, alias, selectorValue, destinationAlias, exists);
	}

	/**
	 * Saves the text value of an element attribute against an alias
	 *
	 * @param attribute		   The name of the attribute to select
	 * @param selector         Either ID, class, xpath, name or css selector
	 * @param alias            If this word is found in the step, it means the selectorValue is found from the data
	 *                         set.
	 * @param selectorValue    The value used in conjunction with the selector to match the element. If alias was set, '
	 *                         this value is found from the data set. Otherwise it is a literal value.
	 * @param destinationAlias The name of the alias to save the text content against
	 * @param exists           If this text is set, an error that would be thrown because the element was not found is
	 *                         ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I save the attribute content of \"([^\"]*)\" from (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\" to the alias "
		+ "\"([^\"]*)\"( if it exists)?")
	public void saveAttributeContent(
		final String attribute,
		final String selector,
		final String alias,
		final String selectorValue,
		final String destinationAlias,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(by));

			final Map<String, String> dataSet = threadDetails.getDataSet();
			dataSet.put(destinationAlias, element.getAttribute(attribute));
			threadDetails.setDataSet(dataSet);
		} catch (final TimeoutException ex) {
			if (StringUtils.isBlank(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Saves the text content of an element against an alias
	 *
	 * @param selector         Either ID, class, xpath, name or css selector
	 * @param alias            If this word is found in the step, it means the selectorValue is found from the data
	 *                         set.
	 * @param selectorValue    The value used in conjunction with the selector to match the element. If alias was set, '
	 *                         this value is found from the data set. Otherwise it is a literal value.
	 * @param destinationAlias The name of the alias to save the text content against
	 * @param exists           If this text is set, an error that would be thrown because the element was not found is
	 *                         ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I save the text content of (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\" to the alias "
		+ "\"([^\"]*)\"( if it exists)?")
	public void saveTextContent(
		final String selector,
		final String alias,
		final String selectorValue,
		final String destinationAlias,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(by));

			final Map<String, String> dataSet = threadDetails.getDataSet();
			dataSet.put(destinationAlias, element.getText());
			threadDetails.setDataSet(dataSet);
		} catch (final TimeoutException ex) {
			if (StringUtils.isBlank(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Saves the text content of an element against an alias. This version extracts the value using javascript, which
	 * means it can return content when the method above does not.
	 *
	 * @param selector         Either ID, class, xpath, name or css selector
	 * @param alias            If this word is found in the step, it means the selectorValue is found from the data
	 *                         set.
	 * @param selectorValue    The value used in conjunction with the selector to match the element. If alias was set, '
	 *                         this value is found from the data set. Otherwise it is a literal value.
	 * @param destinationAlias The name of the alias to save the text content against
	 * @param exists           If this text is set, an error that would be thrown because the element was not found is
	 *                         ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I save the text content of (?:a|an|the) hidden element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\" to the alias \"([^\"]*)\""
		+ "( if it exists)?")
	public void saveHiddenTextContent(
		final String selector,
		final String alias,
		final String selectorValue,
		final String destinationAlias,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));

			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
			final String text = js.executeScript("return arguments[0].textContent;", element).toString();

			final Map<String, String> dataSet = threadDetails.getDataSet();
			dataSet.put(destinationAlias, text);
			threadDetails.setDataSet(dataSet);
		} catch (final TimeoutException ex) {
			if (StringUtils.isBlank(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Text Entry">

	/**
	 * Clears the contents of an element
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set,
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 */
	@When("^I clear (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"")
	public void clearElement(final String selector, final String alias, final String selectorValue) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
		final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
		element.clear();
	}

	/**
	 * Press the CTRL-A keys to the active element
	 */
	@When("^I press CTRL-A on the active element")
	public void pressCtrlAStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.chord(Keys.CONTROL, "a"));
		sleep(defaultWait);
	}

	/**
	 * Press the CMD-A keys to the active element
	 */
	@When("^I press CMD-A on the active element")
	public void pressCmdAStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.chord(Keys.COMMAND, "a"));
		sleep(defaultWait);
	}

	/**
	 * Press the CMD-A or CTRL-A keys to the active element depending on the client os
	 */
	@When("^I select all the text in the active element")
	public void pressCmdOrCtrlAStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();

		if (SystemUtils.IS_OS_MAC) {
			element.sendKeys(Keys.chord(Keys.COMMAND, "a"));
		} else {
			element.sendKeys(Keys.chord(Keys.CONTROL, "a"));
		}
		sleep(defaultWait);
	}

	@When("^I press Delete on the active element(?: \"(\\d+)\" times)?")
	public void pressDeleteStep(final Integer times) {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();

		for (int i = 0; i < ObjectUtils.defaultIfNull(times, 1); ++i) {
			element.sendKeys(Keys.DELETE);
			sleep(defaultWait);
		}
	}

	/**
	 * Press the tab key on the active element
	 */
	@When("^I press tab on the active element")
	public void pressTabStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.TAB);
		sleep(defaultWait);
	}

	/**
	 * Press the tab key on the active element
	 */
	@When("^I press(?: the)? down arrow(?: key)? on the active element")
	public void pressDownArrowStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.ARROW_DOWN);
		sleep(defaultWait);
	}

	/**
	 * Presses the backspace key on the active element
	 */
	@When("^I press(?: the)? backspace(?: key)? on the active element")
	public void pressBackspaceStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.BACK_SPACE);
		sleep(defaultWait);
	}

	/**
	 * Presses the enter key on the active element
	 */
	@When("^I press(?: the)? enter(?: key)? on the active element")
	public void pressEnterStep() {
		final WebElement element = threadDetails.getWebDriver().switchTo().activeElement();
		element.sendKeys(Keys.ENTER);
		sleep(defaultWait);
	}

	/**
	 * sendKeys will often not trigger the key up event, which some elements of the page need in order to complete their
	 * processing. <p> Calling this step after you have populated the field can be used as a workaround.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set,
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 */
	@When("I dispatch a key up event on (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"")
	public void triggetKeyUp(final String selector, final String alias, final String selectorValue) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
		final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
		final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
		js.executeScript("arguments[0].dispatchEvent(new KeyboardEvent(\"keyup\"));", element);
		sleep(defaultWait);
	}

	/**
	 * sendKeys will often not trigger the key events, which some elements of the page need in order to complete their
	 * processing. <p> Calling this step after you have populated the field can be used as a workaround.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set,
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 */
	@When("I dispatch a key press event on (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"")
	public void triggetKeyPress(final String selector, final String alias, final String selectorValue) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
		final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
		final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
		js.executeScript("arguments[0].dispatchEvent(new KeyboardEvent(\"keypress\"));", element);
		sleep(defaultWait);
	}

	/**
	 * sendKeys will often not trigger the key down event, which some elements of the page need in order to complete
	 * their processing. <p> Calling this step after you have populated the field can be used as a workaround.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set,
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 */
	@When("I dispatch a key down event (?:a|an|the) element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"")
	public void triggetKeyDown(final String selector, final String alias, final String selectorValue) {
		final By by = getBy(selector, " alias".equals(alias), selectorValue);
		final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
		final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
		final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
		js.executeScript("arguments[0].dispatchEvent(new KeyboardEvent(\"keydown\"));", element);
		sleep(defaultWait);
	}

	/**
	 * Populate an element with some text, and submits it.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set,
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 * @param contentAlias  If this word is found in the step, it means the content is found from the data set.
	 * @param content       The content to populate the element with. If contentAlias was set, this value is found from
	 *                      the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I populate (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\" with( alias)? \"([^\"]*)\" and submit( if it exists)?$")
	public void populateElementAndSubmitStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String contentAlias,
		final String content,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			// Simulate key presses
			final String value = " alias".equals(contentAlias)
				? threadDetails.getDataSet().get(content) : content;

			checkState(value != null, "the aliased content value does not exist");

			for (final Character character : value.toCharArray()) {
				sleep(KEY_STROKE_DELAY);
				element.sendKeys(character.toString());
			}

			sleep(KEY_STROKE_DELAY);
			element.submit();
			sleep(defaultWait);
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Populate an element with some text
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, '
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 * @param contentAlias  If this word is found in the step, it means the content is found from the data set.
	 * @param content       The content to populate the element with. If contentAlias was set, this value is found from
	 *                      the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 * @param empty         Skips the step if the element is not empty
	 * @param delay			An optional value that defines how long to wait before each simulated keypress. This is
	 *                      useful for setting a longer delay fields that perform ajax request in response to key pressed.
	 */
	@SuppressWarnings("checkstyle:parameternumber")
	@When("^I populate (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? of "
		+ "\"([^\"]*)\" with( alias)? \"([^\"]*)\"( if it exists)?( if it is empty)?"
		+ "(?: with a keystroke delay of \"(\\d+)\" milliseconds)?$")
	public void populateElementStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String contentAlias,
		final String content,
		final String exists,
		final String empty,
		final Integer delay) {
		try {
			final Integer fixedDelay = delay == null ? KEY_STROKE_DELAY : delay;

			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			/*
				See if the element is blank, or contains only underscores (as you might find in
				an empty phone number field for example
			 */

			final boolean processElement = !" if it is empty".equals(empty)
				|| StringUtils.isBlank(element.getAttribute("value"))
				|| BLANK_OR_MASKED_RE.matcher(element.getAttribute("value")).matches();

			if (processElement) {
				// Simulate key presses
				final String textValue = " alias".equals(contentAlias)
					? threadDetails.getDataSet().get(content) : content;

				checkState(textValue != null, "the aliased text value does not exist");

				for (final Character character : textValue.toCharArray()) {
					sleep(fixedDelay);
					element.sendKeys(character.toString());
				}
				sleep(defaultWait);
			}
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Populate an element with some text
	 *
	 * @param attributeNameAlias  If this word is found in the step, it means the attributeName is found from the data
	 *                            set.
	 * @param attributeName       The name of the attribute to match.
	 * @param attributeValueAlias If this word is found in the step, it means the attributeValue is found from the data
	 *                            set.
	 * @param attributeValue      The value of the attribute to match
	 * @param contentAlias        If this word is found in the step, it means the content is found from the data set.
	 * @param content             The content to populate the element with
	 * @param exists              If this text is set, an error that would be thrown because the element was not found
	 *                            is ignored. Essentially setting this text makes this an optional statement.
	 * @param empty               If this phrase exists, the step will be skipped if the element is not empty
	 */
	@SuppressWarnings("checkstyle:parameternumber")
	@When("^I populate (?:a|an|the) element with (?:a|an|the) attribute( alias)? of \"([^\"]*)\" "
		+ "equal to( alias)? \"([^\"]*)\" with( alias)? \"([^\"]*)\""
		+ "( if it exists)?( if it is empty)?$")
	public void populateElementWithAttrStep(
		final String attributeNameAlias,
		final String attributeName,
		final String attributeValueAlias,
		final String attributeValue,
		final String contentAlias,
		final String content,
		final String exists,
		final String empty) {
		try {
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			final String value = " alias".equals(attributeValueAlias)
				? threadDetails.getDataSet().get(attributeValue) : attributeValue;

			checkState(attr != null, "the aliased attribute name does not exist");
			checkState(value != null, "the aliased attribute value does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + value + "']")));

			/*
				See if the element is blank, or contains only underscores (as you might find in
				an empty phone number field for example
			 */
			final boolean processElement = !" if it is empty".equals(empty)
				|| StringUtils.isBlank(element.getAttribute("value"))
				|| BLANK_OR_MASKED_RE.matcher(element.getAttribute("value")).matches();

			if (processElement) {
				// Simulate key presses
				final String textValue = " alias".equals(contentAlias)
					? threadDetails.getDataSet().get(content) : content;

				checkState(textValue != null, "the aliased text value does not exist");

				for (final Character character : textValue.toCharArray()) {
					sleep(KEY_STROKE_DELAY);
					element.sendKeys(character.toString());
				}
				sleep(defaultWait);
			}
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Populates an element with a random number
	 *
	 * @param selector         Either ID, class, xpath, name or css selector
	 * @param alias            If this word is found in the step, it means the selectorValue is found from the data
	 *                         set.
	 * @param selectorValue    The value used in conjunction with the selector to match the element. If alias was set,
	 *                         this value is found from the data set. Otherwise it is a literal value.
	 * @param randomStartAlias If this word is found in the step, it means the randomStart is found from the data set.
	 * @param randomStart      The start of the range of random numbers to select from
	 * @param randomEndAlias   If this word is found in the step, it means the randomEnd is found from the data set.
	 * @param randomEnd        The end of the range of random numbers to select from
	 * @param exists           If this text is set, an error that would be thrown because the element was not found is
	 *                         ignored. Essentially setting this text makes this an optional statement.
	 */
	@SuppressWarnings("checkstyle:parameternumber")
	@When("^I populate (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\" with a random number between( alias)? \"([^\"]*)\" and( alias)? "
		+ "\"([^\"]*)\"( if it exists)?$")
	public void populateElementWithRandomNumberStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String randomStartAlias,
		final String randomStart,
		final String randomEndAlias,
		final String randomEnd,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			final String startValue = " alias".equals(randomStartAlias)
				? threadDetails.getDataSet().get(randomStart) : randomStart;
			final String endValue = " alias".equals(randomEndAlias)
				? threadDetails.getDataSet().get(randomEnd) : randomEnd;

			checkState(startValue != null, "the aliased start value does not exist");
			checkState(endValue != null, "the aliased end value does not exist");

			final Integer int1 = Integer.parseInt(startValue);
			final Integer int2 = Integer.parseInt(endValue);
			final Integer random = SecureRandom.getInstance("SHA1PRNG")
				.nextInt(Math.abs(int2 - int1)) + Math.min(int1, int2);

			// Simulate key presses
			for (final Character character : random.toString().toCharArray()) {
				sleep(KEY_STROKE_DELAY);
				element.sendKeys(character.toString());
			}
			sleep(defaultWait);
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		} catch (final NoSuchAlgorithmException ex) {
			/*
				This shouldn't happen
			 */
			LOGGER.error("Exception thrown when trying to create a SecureRandom instance", ex);
		}
	}

	/**
	 * Populate an element with some text, and submits it.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, '
	 *                      this value is found from the data set. Otherwise it is a literal value.
	 * @param contentAlias  If this word is found in the step, it means the content is found from the data set.
	 * @param content       The content to populate the element with. If contentAlias was set, this value is found from
	 *                      the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I populate (?:a|an|the) hidden element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\" with( alias)? \"([^\"]*)\"( if it exists)?$")
	public void populateHiddenElementAndSubmitStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String contentAlias,
		final String content,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));

			final String textValue = " alias".equals(contentAlias)
				? threadDetails.getDataSet().get(content) : content;

			checkState(textValue != null, "the aliased text value does not exist");

			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
			js.executeScript(
				"arguments[0].value = '"
					+ SINGLE_QUOTE_RE.matcher(textValue).replaceAll("\\'")
					+ "';", element);

			sleep(defaultWait);
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Selection and Focus">

	/**
	 * Focuses on an element. <p> Often with text fields that have some kind of mask you need to first focus on the
	 * element before populating it, otherwise you might not enter all characters correctly.
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I focus(?: on)? (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\"( if it exists)?$")
	public void focusElementStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebElement element = threadDetails.getWebDriver().findElement(by);
			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
			js.executeScript("arguments[0].focus();", element);
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Clicking">

	/**
	 * Clicks on an element
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\"( if it exists)?$")
	public void clickElementStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));
			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();

			/*
				Account for PhantomJS issues clicking certain types of elements
			 */
			final boolean treatAsHiddenElement = BROWSER_INTEROP_UTILS.treatElementAsHidden(
				threadDetails.getWebDriver(), element, js);

			if (treatAsHiddenElement) {
				clickHiddenElementStep(selector, alias, selectorValue, exists);
			} else {
				element.click();
				sleep(defaultWait);
			}
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Clicks on an element that may or may not be visible on the page
	 *
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) hidden element with (?:a|an|the) (ID|class|xpath|name|css selector)( alias)? "
		+ "of \"([^\"]*)\"( if it exists)?$")
	public void clickHiddenElementStep(
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {

		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();

			/*
				PhantomJS doesn't support the click method, so "element.click()" won't work
				here. We need to dispatch the event instead.
			 */
			js.executeScript("var ev = document.createEvent('MouseEvent');"
				+ "    ev.initMouseEvent("
				+ "        'click',"
				+ "        true /* bubble */, true /* cancelable */,"
				+ "        window, null,"
				+ "        0, 0, 0, 0, /* coordinates */"
				+ "        false, false, false, false, /* modifier keys */"
				+ "        0 /*left*/, null"
				+ "    );"
				+ "    arguments[0].dispatchEvent(ev);", element);
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Some applications use mouse events instead of clicks, and PhantomJS will often need us to supply these events
	 * manually.
	 *
	 * @param event         The mouse event we want to generate (mousedown, mouseup etc)
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I \"(.*?)\" on (?:a|an|the) hidden element with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"( if it exists)?$")
	public void mouseEventHiddenElementStep(
		final String event,
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {

		try {
			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();

			/*
				Just like the click, sometimes we need to trigger mousedown events manually
			 */
			js.executeScript("var ev = document.createEvent('MouseEvent');"
				+ "    ev.initMouseEvent("
				+ "        '" + event + "',"
				+ "        true /* bubble */, true /* cancelable */,"
				+ "        window, null,"
				+ "        0, 0, 0, 0, /* coordinates */"
				+ "        false, false, false, false, /* modifier keys */"
				+ "        0 /*left*/, null"
				+ "    );"
				+ "    arguments[0].dispatchEvent(ev);", element);
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Clicks a link on the page
	 *
	 * @param alias       If this word is found in the step, it means the linkContent is found from the data set.
	 * @param linkContent The text content of the link we are clicking
	 * @param exists      If this text is set, an error that would be thrown because the element was not found is
	 *                    ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) link with the text content of( alias)? \"([^\"]*)\"( if it exists)?$")
	public void clickLinkStep(final String alias, final String linkContent, final String exists) {
		try {
			final String text = " alias".equals(alias)
				? threadDetails.getDataSet().get(linkContent) : linkContent;

			checkState(text != null, "the aliased link content does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.presenceOfElementLocated(By.linkText(text)));
			element.click();
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Clicks a link that may or may not be visible on the page
	 *
	 * @param alias       If this word is found in the step, it means the linkContent is found from the data set.
	 * @param linkContent The text content of the link we are clicking
	 * @param exists      If this text is set, an error that would be thrown because the element was not found is
	 *                    ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) hidden link with the text content( alias)? of \"([^\"]*)\"( if it exists)?$")
	public void clickHiddenLinkStep(
		final String alias,
		final String linkContent,
		final String exists) {

		try {
			final String text = " alias".equals(alias)
				? threadDetails.getDataSet().get(linkContent) : linkContent;

			checkState(text != null, "the aliased link content does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.presenceOfElementLocated(By.linkText(text)));
			final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
			js.executeScript("arguments[0].click();", element);
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Clicks an element on the page selected via its attributes
	 *
	 * @param attributeNameAlias  If this word is found in the step, it means the attributeName is found from the data
	 *                            set.
	 * @param attributeName       The name of the attribute to match.
	 * @param attributeValueAlias If this word is found in the step, it means the attributeValue is found from the data
	 *                            set.
	 * @param attributeValue      The value of the attribute to match
	 * @param exists              If this text is set, an error that would be thrown because the element was not found
	 *                            is ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) element with (?:a|an|the) attribute( alias)? of \"([^\"]*)\" equal to( alias)? "
		+ "\"([^\"]*)\"( if it exists)?$")
	public void clickElementWithAttrStep(
		final String attributeNameAlias,
		final String attributeName,
		final String attributeValueAlias,
		final String attributeValue,
		final String exists) {

		try {
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			final String value = " alias".equals(attributeValueAlias)
				? threadDetails.getDataSet().get(attributeValue) : attributeValue;

			checkState(attr != null, "the aliased attribute name does not exist");
			checkState(value != null, "the aliased attribute value does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + value + "']")));
			element.click();
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Clicks on an element with a random number
	 *
	 * @param attributeName      Either ID, class, xpath, name or css selector
	 * @param attributeNameAlias If this word is found in the step, it means the selectorValue is found from the data
	 *                           set.
	 * @param randomStartAlias   If this word is found in the step, it means the randomStart is found from the data
	 *                           set.
	 * @param randomStart        The start of the range of random numbers to select from
	 * @param randomEndAlias     If this word is found in the step, it means the randomEnd is found from the data set.
	 * @param randomEnd          The end of the range of random numbers to select from
	 * @param exists             If this text is set, an error that would be thrown because the element was not found is
	 *                           ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|an|the) element with (?:a|an|the) attribute( alias)? of \"([^\"]*)\" "
		+ "with a random number between( alias)? \"([^\"]*)\" and( alias)? \"([^\"]*)\""
		+ "( if it exists)?$")
	public void clickElementWithRandomNumberStep(
		final String attributeNameAlias,
		final String attributeName,
		final String randomStartAlias,
		final String randomStart,
		final String randomEndAlias,
		final String randomEnd,
		final String exists) {

		try {
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			checkState(attr != null, "the aliased attribute name does not exist");

			final String startValue = " alias".equals(randomStartAlias)
				? threadDetails.getDataSet().get(randomStart) : randomStart;
			final String endValue = " alias".equals(randomEndAlias)
				? threadDetails.getDataSet().get(randomEnd) : randomEnd;

			checkState(startValue != null, "the aliased start value does not exist");
			checkState(endValue != null, "the aliased end value does not exist");

			final Integer int1 = Integer.parseInt(startValue);
			final Integer int2 = Integer.parseInt(endValue);
			final Integer random = SecureRandom.getInstance("SHA1PRNG").nextInt(
				Math.abs(int2 - int1)) + Math.min(int1, int2);

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + random + "']")));

			element.click();
			sleep(defaultWait);
		} catch (final TimeoutException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		} catch (final NoSuchAlgorithmException ignored) {
			/*
				This shouldn't happen
			 */
		}
	}

	/**
	 * Clicks an element on the page of the datepicker.
	 *
	 * @param attributeNameAlias  If this word is found in the step, it means the attributeName is found from the data
	 *                            set.
	 * @param attributeName       The name of the attribute to match.
	 * @param attributeValueAlias If this word is found in the step, it means the attributeValue is found from the data
	 *                            set.
	 * @param attributeValue      The value of the attribute to match - Currently supported values are today and
	 *                            tomorrow only.
	 * @param exists              If this text is set, an error that would be thrown because the element was not found
	 *                            is ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I click (?:a|the) datepicker with (?:a|an|the) attribute( alias)? of \"([^\"]*)\" equal to( alias)? "
		+ "\"([^\"]*)\"( if it exists)?$")
	public void clickElementWithDatepicker(
		final String attributeNameAlias,
		final String attributeName,
		final String attributeValueAlias,
		final String attributeValue,
		final String exists) {

		try {
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			final String value = " alias".equals(attributeValueAlias)
				? threadDetails.getDataSet().get(attributeValue) : attributeValue;

			checkState(attr != null, "the aliased attribute name does not exist");
			checkState(value != null, "the aliased attribute value does not exist");

			LocalDate theDate = LocalDate.now();
			int today = theDate.getDayOfMonth();
			int tomorrow = theDate.getDayOfMonth() + 1;
			int dateValue = "today".equals(value) ? today : tomorrow;

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + dateValue + "']")));
			element.click();
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Dropdown List Selection">

	/**
	 * Select an item from a drop down list
	 *
	 * @param itemAlias     If this word is found in the step, it means the itemName is found from the data set.
	 * @param itemName      The text of the item to select
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I select( alias)? \"([^\"]*)\" from (?:a|the) drop down list with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"( if it exists)?$")
	public void selectDropDownListItemStep(
		final String itemAlias,
		final String itemName,
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {

		try {
			final String selection = " alias".equals(itemAlias)
				? threadDetails.getDataSet().get(itemName) : itemName;

			checkState(selection != null, "the aliased item name does not exist");

			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			final Select select = new Select(element);
			select.selectByVisibleText(selection);

			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Select an item from a drop down list
	 *
	 * @param itemAlias           If this word is found in the step, it means the itemName is found from the data set.
	 * @param itemName            The text of the item to select
	 * @param attributeNameAlias  If this word is found in the step, it means the attributeName is found from the data
	 *                            set.
	 * @param attributeName       The name of the attribute to match.
	 * @param attributeValueAlias If this word is found in the step, it means the attributeValue is found from the data
	 *                            set.
	 * @param attributeValue      The value of the attribute to match
	 * @param exists              If this text is set, an error that would be thrown because the element was not found
	 *                            is ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I select( alias)? \"([^\"]*)\" from (?:a|the) drop down list with (?:a|an|the) attribute of( alias)? "
		+ "\"([^\"]*)\" equal to( alias)? \"([^\"]*)\"( if it exists)?$")
	public void selectDropDownListAttrStep(
		final String itemAlias,
		final String itemName,
		final String attributeNameAlias,
		final String attributeName,
		final String attributeValueAlias,
		final String attributeValue,
		final String exists) {

		try {
			final String selection = " alias".equals(itemAlias)
				? threadDetails.getDataSet().get(itemName) : itemName;
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			final String value = " alias".equals(attributeValueAlias)
				? threadDetails.getDataSet().get(attributeValue) : attributeValue;

			checkState(selection != null, "the aliased item name does not exist");
			checkState(attr != null, "the aliased attribute name does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + value + "']")));

			final Select select = new Select(element);
			select.selectByVisibleText(selection);

			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Select an item index from a drop down list
	 *
	 * @param itemAlias     If this word is found in the step, it means the itemName is found from the data set.
	 * @param itemIndex     The index of the item to select
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param alias         If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I select option(?: number)?( alias)? \"([^\"]*)\" from (?:a|the) drop down list with (?:a|an|the) "
		+ "(ID|class|xpath|name|css selector)( alias)? of \"([^\"]*)\"( if it exists)?$")
	public void selectDropDownListIndexStep(
		final String itemAlias,
		final String itemIndex,
		final String selector,
		final String alias,
		final String selectorValue,
		final String exists) {
		try {
			final String selection = " alias".equals(itemAlias)
				? threadDetails.getDataSet().get(itemIndex) : itemIndex;

			checkState(selection != null, "the aliased item index does not exist");

			final By by = getBy(selector, " alias".equals(alias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			final Select select = new Select(element);
			select.selectByIndex(Integer.parseInt(selection));
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	/**
	 * Select an item index from a drop down list
	 *
	 * @param itemAlias           If this word is found in the step, it means the itemName is found from the data set.
	 * @param itemIndex           The index of the item to select
	 * @param attributeNameAlias  If this word is found in the step, it means the attributeName is found from the data
	 *                            set.
	 * @param attributeName       The name of the attribute to match.
	 * @param attributeValueAlias If this word is found in the step, it means the attributeValue is found from the data
	 *                            set.
	 * @param attributeValue      The value of the attribute to match
	 * @param exists              If this text is set, an error that would be thrown because the element was not found
	 *                            is ignored. Essentially setting this text makes this an optional statement.
	 */
	@When("^I select option(?: number)?( alias)? \"([^\"]*)\" from (?:a|the) drop down list with (?:a|an|the) "
		+ "attribute of( alias)? \"([^\"]*)\" equal to( alias)? \"([^\"]*)\"( if it exists)?$")
	public void selectDropDownListWithAttrItemStep(
		final String itemAlias,
		final String itemIndex,
		final String attributeNameAlias,
		final String attributeName,
		final String attributeValueAlias,
		final String attributeValue,
		final String exists) {
		try {
			final String selection = " alias".equals(itemAlias)
				? threadDetails.getDataSet().get(itemIndex) : itemIndex;
			final String attr = " alias".equals(attributeNameAlias)
				? threadDetails.getDataSet().get(attributeName) : attributeName;
			final String value = " alias".equals(attributeValueAlias)
				? threadDetails.getDataSet().get(attributeValue) : attributeValue;

			checkState(selection != null, "the aliased item index does not exist");
			checkState(attr != null, "the aliased attribute name does not exist");

			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(
				ExpectedConditions.elementToBeClickable(
					By.cssSelector("[" + attr + "='" + value + "']")));

			final Select select = new Select(element);
			select.selectByIndex(Integer.parseInt(selection));
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Validation">

	/**
	 * Verify the title in the browser
	 * @param browserTitle Defines what the browser title should be
	 */
	@Then("^the browser title should be \"([^\"]*)\"$")
	public void checkBrowserTitleStep(final String browserTitle) {
		Assert.assertEquals(browserTitle, threadDetails.getWebDriver().getTitle());
		sleep(defaultWait);
	}

	/**
	 * @param selector      Either ID, class, xpath, name or css selector
	 * @param selectorAlias If this word is found in the step, it means the selectorValue is found from the data set.
	 * @param selectorValue The value used in conjunction with the selector to match the element. If alias was set, this
	 *                      value is found from the data set. Otherwise it is a literal value.
	 * @param classAlias    If this word is found in the step, it means the classValue is found from the data set.
	 * @param classValue    The class value
	 * @param exists        If this text is set, an error that would be thrown because the element was not found is
	 *                      ignored. Essentially setting this text makes this an optional statement.
	 */
	@Then("^the element with the (ID|class|xpath|name|css selector)( alias)? \"([^\"]*)\" should have a "
		+ "class( alias)? of \"([^\"]*)\"( if it exists)?$")
	public void checkElementClassStep(
		final String selector,
		final String selectorAlias,
		final String selectorValue,
		final String classAlias,
		final String classValue,
		final String exists) {
		try {
			final By by = getBy(selector, " alias".equals(selectorAlias), selectorValue);
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));

			final String className = " alias".equals(classAlias)
				? threadDetails.getDataSet().get(classValue) : classValue;

			checkState(className != null, "the aliased class name does not exist");

			final Iterable<String> split = Splitter.on(' ')
				.trimResults()
				.omitEmptyStrings()
				.split(element.getAttribute("class"));

			Assert.assertTrue(Iterables.contains(split, className));
			sleep(defaultWait);
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Util Methods">

	private void sleep(final int sleep) {
		try {
			Thread.sleep(sleep);
		} catch (final InterruptedException ignored) {
			/*
				We don't actually care about this exception
			 */
		}
	}

	/**
	 * Map common selectors to Selenium selection objects
	 *
	 * @param selector   The name of the selector
	 * @param valueAlias true if the value is an alias, false otherwose
	 * @param value      The alias key or value to use with the selector
	 * @return The Selenium selection object
	 */
	private By getBy(final String selector, final boolean valueAlias, final String value) {
		final String fixedValue = valueAlias ? threadDetails.getDataSet().get(value) : value;

		checkState(StringUtils.isNotBlank(fixedValue), "Selector or alias is blank");

		if ("ID".equals(selector)) {
			return By.id(fixedValue);
		}

		if ("xpath".equals(selector)) {
			return By.xpath(fixedValue);
		}

		if ("class".equals(selector)) {
			return By.cssSelector("." + fixedValue);
		}

		if ("name".equals(selector)) {
			return By.name(fixedValue);
		}

		if ("css selector".equals(selector)) {
			return By.cssSelector(fixedValue);
		}

		throw new InvalidInputException("Unexpected selector");
	}

	// </editor-fold>

	// <editor-fold desc="A&G App Hacks">

	/**
	 * The postcode suggestion box is a pain in the ass. It won't be triggered without a keyup event, and browsers like
	 * Firefox and Safari don't seem to trigger this event if the browser is no focused. It also fails to trigger in
	 * Browserstack. <p> So, to work around this, we use a utility method from the branding project in jquery.address.js
	 * to set the value of the post code.
	 *
	 * @param postcode The postcode to enter into the suburb text box
	 * @param alias Include this text if the value to be added to the postcode box is an alias
	 */
	@When("I autoselect the post code of( alias)? \"([^\"]*)\"")
	public void autoselectPostcode(final String alias, final String postcode) {
		final String postcodeValue = " alias".equals(alias)
			? threadDetails.getDataSet().get(postcode) : postcode;

		final By by = getBy("class", false, Constants.POSTCODE_CLASS);
		final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);

		final WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));
		final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
		js.executeScript("arguments[0].autoSelectSuburb('" + postcodeValue + "');", element);
		sleep(defaultWait);
	}

	// </editor-fold>

	// <editor-fold desc="Login">

	/**
	 * This code is supposed to populate the login dialog, but it actually doesn't work with most modern browsers.
	 *
	 * @param username The username
	 * @param password The password
	 * @param exists   If this text is set, an error that would be thrown because the element was not found is ignored.
	 *                 Essentially setting this text makes this an optional statement.
	 */
	@When("I log in with username \"([^\"]*)\" and password \"([^\"]*)\"( if it exists)?$")
	public void login(final String username, final String password, final String exists) {
		try {
			final WebDriverWait wait = new WebDriverWait(threadDetails.getWebDriver(), WAIT);
			final Alert alert = wait.until(ExpectedConditions.alertIsPresent());
			alert.authenticateUsing(new UserAndPassword(username, password));
		} catch (final TimeoutException | NoSuchElementException ex) {
			if (!" if it exists".equals(exists)) {
				throw ex;
			}
		}
	}

	// </editor-fold>

	// <editor-fold desc="Tabs and Windows">

	/**
	 * Switchs to the specified tab. This is useful when you open a link that opens in a new window.
	 *
	 * @param tabIndex The index of the new tab. Usually 1 (the original tab will be 0)
	 */
	@When("I switch to tab \"(\\d+)\"$")
	public void switchTabs(final String tabIndex) {
		List<String> tabs2 = new ArrayList<>(threadDetails.getWebDriver().getWindowHandles());
		threadDetails.getWebDriver().switchTo().window(tabs2.get(Integer.parseInt(tabIndex)));
		sleep(defaultWait);
	}

	/**
	 * Switchs to the specified window. This is useful when you open a link that opens in a new window.
	 */
	@When("I switch to new window")
	public void switchWindows() {
		threadDetails.getWebDriver().getWindowHandles().stream()
			.filter(e -> !e.equals(threadDetails.getWebDriver().getWindowHandle()))
			.forEach(e -> threadDetails.getWebDriver().switchTo().window(e));

		sleep(defaultWait);
	}

	// </editor-fold>

	// <editor-fold desc="Debugging">

	@When("I dump the alias map to the console$")
	public void dumpAliasMap() {
		LOGGER.info("Dump of the alias map.");
		for (final String key : threadDetails.getDataSet().keySet()) {
			LOGGER.info("{}: {}", key, threadDetails.getDataSet().get(key));
		}
	}

	/**
	 * When comparing the performance of a page in a recorded video, it is useful to have some kind of visual indication
	 * when the script is started. This step dumps some text to a blank page before a URL is loaded.
	 */
	@When("I display a starting marker$")
	public void displayStartingMarker() {
		final JavascriptExecutor js = (JavascriptExecutor) threadDetails.getWebDriver();
		js.executeScript("javascript:window.document.body.innerHTML = "
			+ "'<div style=\"margin: 50px; font-size: 20px\">Starting</div>'");
	}

	// </editor-fold>
}
