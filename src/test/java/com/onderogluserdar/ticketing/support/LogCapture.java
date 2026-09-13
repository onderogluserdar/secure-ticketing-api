package com.onderogluserdar.ticketing.support;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Minimal root-logger tap, for the two assertions that are genuinely about log output: an
 * unexpected failure must log its error id, and expected rejections must not log at ERROR.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture attach() {
        return new LogCapture();
    }

    public List<ILoggingEvent> errors() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
    }

    /** Formatted message plus the thrown stack trace, which is where a sanitized id has to appear. */
    public String errorText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : errors()) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getClassName())
                        .append(": ")
                        .append(event.getThrowableProxy().getMessage())
                        .append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
