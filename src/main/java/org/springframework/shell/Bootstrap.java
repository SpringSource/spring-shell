/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.shell;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.shell.core.ExitShellRequest;
import org.springframework.shell.core.JLineShellComponent;
import org.springframework.shell.core.Shell;
import org.springframework.shell.plugin.PluginConfig;
import org.springframework.shell.support.logging.HandlerUtils;
import org.springframework.util.StopWatch;

/**
 * Loads a {@link Shell} using Spring IoC container.
 *
 * @author Ben Alex
 * @since 1.0
 */
public class Bootstrap {

  private static Bootstrap                      bootstrap;
  private        JLineShellComponent            shell;
  private        ConfigurableApplicationContext ctx;
  private static StopWatch sw = new StopWatch("Spring Shell");
  private static SimpleShellCommandLineOptions options;

  public static void main(String[] args) throws IOException {
    sw.start();
    options = SimpleShellCommandLineOptions.parseCommandLine(args);

    for(Map.Entry<String, String> entry : options.extraSystemProperties.entrySet()) {
      System.setProperty(entry.getKey(), entry.getValue());
    }
    ExitShellRequest exitShellRequest;
    try {
      bootstrap = new Bootstrap();
      exitShellRequest = bootstrap.run(options.executeThenQuit);
    } catch(RuntimeException t) {
      throw t;
    } finally {
      HandlerUtils.flushAllHandlers(Logger.getLogger(""));
    }

    System.exit(exitShellRequest.getExitCode());
  }

  public Bootstrap() throws IOException {
    //setupLogging();
    ctx = new AnnotationConfigApplicationContext(
        ShellConfig.class
    );
    AnnotationConfigApplicationContext pluginCtx = new AnnotationConfigApplicationContext(PluginConfig.class);
    pluginCtx.setParent(ctx);

    shell = ctx.getBean(JLineShellComponent.class);
    shell.setHistorySize(options.historySize);
    if(options.executeThenQuit != null) {
      shell.setPrintBanner(false);
    }
  }

  // seems on JDK 1.6.0_18 or higher causes the output to disappear
  private void setupLogging() {
    // Ensure all JDK log messages are deferred until a target is registered
    Logger rootLogger = Logger.getLogger("");
    HandlerUtils.wrapWithDeferredLogHandler(rootLogger, Level.SEVERE);

    // Set a suitable priority level on Spring Framework log messages
    Logger sfwLogger = Logger.getLogger("org.springframework");
    sfwLogger.setLevel(Level.WARNING);

    // Set a suitable priority level on Roo log messages
    // (see ROO-539 and HandlerUtils.getLogger(Class))
    Logger rooLogger = Logger.getLogger("org.springframework.shell");
    rooLogger.setLevel(Level.FINE);
  }


  protected ExitShellRequest run(String[] executeThenQuit) {

    ExitShellRequest exitShellRequest;

    if(null != executeThenQuit) {
      boolean successful = false;
      exitShellRequest = ExitShellRequest.FATAL_EXIT;

      for(String cmd : executeThenQuit) {
        successful = shell.executeCommand(cmd);
        if(!successful) {
          break;
        }
      }

      //if all commands were successful, set the normal exit status
      if(successful) {
        exitShellRequest = ExitShellRequest.NORMAL_EXIT;
      }
    } else {
      shell.start();
      shell.promptLoop();
      exitShellRequest = shell.getExitShellRequest();
      if(exitShellRequest == null) {
        // shouldn't really happen, but we'll fallback to this anyway
        exitShellRequest = ExitShellRequest.NORMAL_EXIT;
      }
      shell.waitForComplete();
    }

    ctx.close();
    sw.stop();
    if(shell.isDevelopmentMode()) {
      System.out.println("Total execution time: " + sw.getLastTaskTimeMillis() + " ms");
    }
    return exitShellRequest;
  }
}