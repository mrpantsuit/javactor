package javactor.akka;

import javactor.JavactorContext.SupervisorDirective;
import javactor.annot.OnException;

public class HandleRuntimeExceptionTestJavactor extends SupervisorJavactor
{
	@OnException
	public SupervisorDirective handle(RuntimeException t) {
		return directive;
	}
}
