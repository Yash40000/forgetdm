package io.forgetdm.provision.loader;

public class NativeLoadException extends RuntimeException {
    public NativeLoadException(String message) { super(message); }
    public NativeLoadException(String message, Throwable cause) { super(message, cause); }
}
