/*
 * Copyright 2011-2013 the original author or authors.
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
package org.springframework.shell.core;

import static org.junit.Assert.assertTrue;
import static org.springframework.shell.core.CommandConstants.DATE_COMMAND;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date command test.
 *
 * @author David Winterfeldt
 */
public class DateTest extends AbstractShellIT {

    final Logger logger = LoggerFactory.getLogger(getClass());

    private final static String PATTERN = "E, MMM d, yyyy h:mm:ss a zzz";

    @Test
    public void testShellStart() throws IOException, ParseException {
        Date now = new Date();

        // wait one second to guarantee dates don't match
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        String result = exec(DATE_COMMAND);
        result = result.substring(6);

        DateFormat formatter = new SimpleDateFormat(PATTERN, Locale.US);

        // date is parseable or exception would be thrown
        Date shellDate = formatter.parse(result);

        logger.info("shell date='{}'", shellDate);

        assertTrue("Shell date should be before date test was started.", now.before(shellDate));
    }

}
