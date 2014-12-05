package javactor.akka;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javactor.msg.TimeoutMsg;
import lombok.Setter;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;

public abstract class EasyUntypedActor extends UntypedActor
{
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface EasySubscribe {
	}
	
	private ImmutableMap<Class<?>,Method> methodsByMessageClass;
	private Cancellable timeoutCancellable;
	@Setter
	private boolean subscribeToEventStream = true;
	
	@Override
	public void preStart() throws Exception
	{
		super.preStart();
		Method[] methods = getClass().getMethods();
		Builder<Class<?>,Method> builder = ImmutableMap.builder();
		for (Method method : methods)
		{
			EasySubscribe annotation = method.getAnnotation(EasySubscribe.class);
			if ( annotation != null ) {
				Class<?>[] parameterTypes = method.getParameterTypes();
				if ( parameterTypes.length <= 0 )
					throw new RuntimeException("@Subscribe method "+method+
						" does not have a parameter.");
				final Class<?> msgClass = parameterTypes[0];
				if ( subscribeToEventStream )
					context().system().eventStream().subscribe(getSelf(), 
						msgClass);
				builder.put(msgClass, method);
			};
		}
		methodsByMessageClass = builder.build();
	}

	@Override
	public void onReceive(Object message) throws Exception
	{
		final Class<? extends Object> msgClass = message.getClass();
		ImmutableSet<Entry<Class<?>, Method>> entrySet = methodsByMessageClass.entrySet();
		for (Entry<Class<?>, Method> entry : entrySet)
		{
			if ( entry.getKey().isAssignableFrom(msgClass) ) {
				entry.getValue().invoke(this, message);
				return;
			}
		}
		unhandled(message);
	}
	
	protected LoggingAdapter log() {
		return Logging.getLogger(getContext().system(), this);
	}
	
	protected void publish(Object event) {
		context().system().eventStream().publish(event);
	}

	protected Cancellable scheduleOnce(FiniteDuration initialDelay, ActorRef to, Object msg,
		ActorRef from)
	{
		return context().system().scheduler().scheduleOnce(initialDelay, 
			to, msg, context().system().dispatcher(), from);
	}

	protected Cancellable schedule(FiniteDuration initialDelay, FiniteDuration interval,
		ActorRef to, Object msg, ActorRef from)
	{
		return context().system().scheduler().schedule(initialDelay, interval, 
			to, msg, context().system().dispatcher(), from);
	}
	
	protected void startWaiting(Object taskInfo)
	{
		timeoutCancellable = scheduleOnce(Duration.create(1, TimeUnit.MINUTES),
			getSelf(), new TimeoutMsg(taskInfo), getSelf());
	}

	protected void doneWaiting()
	{
		timeoutCancellable.cancel();
	}
	
	@EasySubscribe
	public void handle(TimeoutMsg msg) {
		if ( timeoutCancellable != null && !timeoutCancellable.isCancelled() ) {
			timedOutWaiting(msg.getTaskInfo());
		}
	}

	protected void timedOutWaiting(Object taskInfo)
	{
		log().error("Got a timeout. "+getClass()+" should override "
			+ "this method for its case-specific behaviour. taskInfo="+taskInfo);
	}
}
