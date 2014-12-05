package javactor.akka;

import javactor.JavactorContext;
import javactor.JavactorContext.JavactorPreparer;
import javactor.JavactorContext.SupervisorDirective;
import javactor.annot.Handle;
import javactor.msg.TestMsg;

public class SupervisorJavactor
{
	JavactorContext ctx;
	private Object child;
	protected SupervisorDirective directive;
	private Object probe;

	public SupervisorJavactor()
	{
		super();
	}

	@Handle
	public void handle(Class<BoomActor> msg)
	{
		probe = ctx.sender();
		child = ctx.actorBuilder(msg, "child")
			.preparer(new JavactorPreparer<BoomActor>()
			{
				@Override
				public void prepare(BoomActor javactor)
				{
					javactor.setProbe(probe);
				}
			})
			.build();
		ctx.msg(child).to(ctx.sender()).fireAndForget();
	}

	@Handle
	public void handle(TestMsg msg)
	{
		ctx.msg(msg).to(child).fireAndForget();
	}

	@Handle
	public void handle(SupervisorDirective msg)
	{
		directive = msg;
		ctx.msg(new Object()).to(ctx.sender()).fireAndForget();
	}

}