package javactor.akka;

import javactor.annot.Handle;
import javactor.msg.TestMsg;

public class MissingExceptionHandleMethodTestJavactor
{
	@Handle
	public void handle(TestMsg msg) {
		throw new RuntimeException("test");
	}
	
}
