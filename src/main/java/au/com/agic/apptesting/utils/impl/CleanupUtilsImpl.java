package au.com.agic.apptesting.utils.impl;

import au.com.agic.apptesting.constants.Constants;
import au.com.agic.apptesting.utils.CleanupUtils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.Iterator;

/**
 * An implementation of the cleanup service
 */
public class CleanupUtilsImpl implements CleanupUtils {

	private static final String[] CLEANUP_EXTENSIONS = {"xml", "txt", "json"};

	@Override
	public void cleanupOldReports() {
		final Iterator<File> iterator =
			FileUtils.iterateFiles(new File("."), CLEANUP_EXTENSIONS, false);
		while (iterator.hasNext()) {
			final File file = iterator.next();
			/*
				Only clean up old report files
			 */
			if (file.getName().startsWith(Constants.THREAD_NAME_PREFIX)) {
				file.delete();
			}
		}
	}
}
