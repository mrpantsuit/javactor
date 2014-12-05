package javactor.annot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javactor.JavactorContext;
import javactor.JavactorContext.SupervisorDirective;

/**
 * Marks methods that will handle exceptions. Methods must accept an {@link Exception} and 
 * return a {@link SupervisorDirective}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnException {
}