package org.springframework.shell;

import java.io.IOException;
import java.util.logging.Logger;

import junit.framework.Assert;

import org.junit.Test;
import org.springframework.shell.core.JLineShellComponent;
import org.springframework.shell.support.logging.HandlerUtils;

public class BootstrapTest {

	@Test
	public void test() throws IOException {
		try {
			Bootstrap bootstrap = new Bootstrap(null);
			JLineShellComponent shell = bootstrap.getJLineShellComponent();
			System.out.println("about to start shell...");
			Assert.assertEquals("Number of CommandMarkers is incorrect", 4, shell.getSimpleParser().getCommandMarkers().size());
			Assert.assertEquals("Number of Converters is incorrect", 16, shell.getSimpleParser().getConverters().size());			
		} catch (RuntimeException t) {
			throw t;
		} finally {
			HandlerUtils.flushAllHandlers(Logger.getLogger(""));
		}
		
	}

}
