package dev.bytevault.controlplane.events;

/** A message that can never be processed as-is. Sent straight to the DLQ without retries. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }
}
