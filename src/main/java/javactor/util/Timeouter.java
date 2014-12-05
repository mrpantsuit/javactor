package javactor.util;

import java.util.concurrent.TimeUnit;

import javactor.Cancellable;
import javactor.JavactorContext;
import javactor.msg.TimeoutMsg;

public class Timeouter
{
	private Cancellable timeoutCancellable;
	private long timeout = 2;
	private TimeUnit timeoutTimeUnit = TimeUnit.SECONDS;

	public void setTimeout(long timeout, TimeUnit timeUnit) {
		this.timeout = timeout;
		this.timeoutTimeUnit = timeUnit;
	}
	
	public void startWaiting(JavactorContext ctx, Object taskInfo)
	{
		doneWaiting();
		timeoutCancellable = ctx.schedule(new TimeoutMsg(taskInfo))
			.toSelf().delay(timeout, timeoutTimeUnit).go();
	}

	public void doneWaiting()
	{
		if ( timeoutCancellable != null )
			timeoutCancellable.cancel();
		timeoutCancellable = null;
	}

}
