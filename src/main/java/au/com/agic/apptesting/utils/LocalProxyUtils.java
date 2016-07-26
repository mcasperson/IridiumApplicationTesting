package au.com.agic.apptesting.utils;

import net.lightbody.bmp.BrowserMobProxy;

import java.io.File;
import java.util.List;
import java.util.Optional;

import javax.validation.constraints.NotNull;

/**
 * Our tests will often need to startup local proxy servers in order to perform tests. This
 * interface provides services for working with these proxies.
 */
public interface LocalProxyUtils<T> {
	/**
	 * Attempts to match the value assigned to the startInternalProxy system property with a
	 * supported internal proxy, and starts it if a match was found.
	 * @param tempFolders A collection that will be populate with any temporary folders to be cleaned up once the
	 *                    test has completed
	 * @return Some kind of interface that can be used to access the proxy. This might be a port, or a client
	 * api object.
	 */
	Optional<ProxyDetails<T>> initProxy(@NotNull final List<File> tempFolders);

	/**
	 * We may need to do aditional configuration between initialising the proxy and starting it.
	 * Starting the proxy is done here if the proxy supports it.
	 * @param proxyDetails The details returned by initProxy
	 */
	void startProxy(final ProxyDetails<T> proxyDetails);
}
