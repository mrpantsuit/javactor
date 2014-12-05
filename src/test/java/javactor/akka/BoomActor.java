package javactor.akka;

import javactor.JavactorContext;
import javactor.annot.Handle;
import javactor.annot.PostRestart;
import javactor.msg.Restarted;
import javactor.msg.TestMsg;
import lombok.Setter;

public class BoomActor
{
	@Setter
	Object probe;
	JavactorContext ctx;
	
	@Handle
	public void handle(TestMsg msg) {
		throw new RuntimeException("test");
	}

	@PostRestart
	public void handle() {
		ctx.msg(new Restarted()).to(probe).fireAndForget();
	}
}
