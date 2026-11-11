package com.exambank.common.security;

/** A caller supplied a non-empty selection that could not be resolved without widening access. */
public class InvalidFileSelectionException extends IllegalArgumentException {
    public static final String CODE = "INVALID_FILE_SELECTION";

    public InvalidFileSelectionException() {
        super("所选文件无效或不可访问");
    }
}
