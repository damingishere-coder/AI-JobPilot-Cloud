package com.getjobs.cloud.resume;

public class ResumeParseException extends RuntimeException {
    public ResumeParseException(String message) {
        super(message);
    }

    public ResumeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
