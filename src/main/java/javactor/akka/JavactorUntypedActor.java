package javactor.akka;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javactor.Cancellable;
import javactor.JavactorContext;
import javactor.JavactorFactory;
import javactor.JavactorContext.JavactorPreparer;
import javactor.JavactorContext.SupervisorDirective;
import javactor.JavactorContext.SupervisorStrategyInfo;
import javactor.JavactorContext.SupervisorStrategyType;
import javactor.annot.Handle;
import javactor.annot.OnException;
import javactor.annot.PostRestart;
import javactor.annot.PostStop;
import javactor.annot.PreRestart;
import javactor.annot.PreStart;
import javactor.msg.TimeoutMsg;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import scala.Option;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorInitializationException;
import akka.actor.ActorKilledException;
import akka.actor.ActorRef;
import akka.actor.AllForOneStrategy;
import akka.actor.DeathPactException;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import akka.japi.Creator;
import akka.japi.Function;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;

@RequiredArgsConstructor
@Log
public class JavactorUntypedActor extends UntypedActor
{
	private static final ImmutableMap<SupervisorDirective, Directive> 
		JAVACTOR_DIRECTIVES_TO_AKKA = ImmutableMap.of(
			SupervisorDirective.STOP, (Directive)SupervisorStrategy.stop(),
			SupervisorDirective.RESTART, SupervisorStrategy.restart(),
			SupervisorDirective.ESCALATE, SupervisorStrategy.escalate(),
			SupervisorDirective.RESUME, SupervisorStrategy.resume()
			);

	private static final class MostToLeastSpecificComparator implements
		Comparator<Class<?>>
	{
		@Override
		public int compare(Class<?> o1, Class<?> o2)
		{
			return o1.isAssignableFrom(o2) ? 1 : -1;
		}
	}
	@Data
	static public class MySupervisorStrategyInfo {
		private final SupervisorStrategyInfo info;
		private final ImmutableMap<Class<?>,Method> onExceptionMethods;
	}
	@SuppressWarnings("serial")
	@RequiredArgsConstructor
	static private final class MyCreator<T> implements Creator<JavactorUntypedActor>
	{
		private final Class<T> javactorClass;
		private final boolean subscribeToEventStream;
		private final JavactorFactory customJavactorFactory;
		private final JavactorFactory defaultJavactorFactory;
		private final JavactorPreparer<T> preparer;

		@Override
		public JavactorUntypedActor create() throws Exception
		{
			@SuppressWarnings("unchecked")
			final T javactor = (T) customJavactorFactory.get(javactorClass);
			if ( preparer != null ) {
				preparer.prepare(javactor);
			}
			return AkkaJavactorBuilder.builder(javactor)
				.subscribeToEventStream(subscribeToEventStream)
				.javactorFactory(defaultJavactorFactory)
				.build();
		}
	}

	@RequiredArgsConstructor
	private final class AkkaJavactorContext implements JavactorContext
	{
		@Data
		private class MyActorBuilder<T> implements ActorBuilder<T> {
			
			private final Class<T> javactorClass;
			private final String actorName;
			private JavactorFactory customFactory;
			private boolean subscribeToEventStream = false;
			private String dispatcherName;
			private JavactorPreparer<T> preparer;

			@Override
			public ActorBuilder<T> subscribeToEventBus()
			{
				this.subscribeToEventStream = true;
				return this;
			}

			@Override
			public ActorBuilder<T> factory(JavactorFactory factory)
			{
				this.customFactory = factory;
				return this;
			}

			@Override
			public Object build()
			{
				JavactorFactory factoryToUse = customFactory == null ? 
					javactorFactory : customFactory;
				if ( javactorFactory == null )
					throw new RuntimeException("createActor called but no "
						+ "javactorFactory has been set");
				Props props = Props.create(new MyCreator<T>(
					javactorClass, subscribeToEventStream, 
					factoryToUse, javactorFactory, preparer));
				if ( dispatcherName != null )
					props = props.withDispatcher(dispatcherName);
				return context().actorOf(props, actorName);
			}

			@Override
			public ActorBuilder<T> preparer(JavactorPreparer<T> preparer)
			{
				this.preparer = preparer;
				return this;
			}

			@Override
			public ActorBuilder<T> dispatcher(String dispatcherName)
			{
				this.dispatcherName = dispatcherName;
				return this;
			}
		}
		
		@Data
		private class MySendBuilder implements SendBuilder {
			private final Object msg;
			private ActorRef to;
			private ActorRef replyTo = getSelf();
			private FiniteDuration timeout;
			private boolean replyToSet = false;
			
			@Override
			public SendBuilder to(Object to)
			{
				this.to = (ActorRef) to;
				return this;
			}

			@Override
			public SendBuilder from(Object replyTo)
			{
				replyToSet = true;
				this.replyTo = (ActorRef) replyTo;
				return this;
			}

			@Override
			public SendBuilder timeout(long timeout, TimeUnit timeUnit)
			{
				this.timeout = Duration.create(timeout, timeUnit);
				return this;
			}

			@Override
			public void fireAndForget()
			{
				if ( timeout != null )
					throw new IllegalArgumentException("Trying to fire and forget "
						+ "with a non null timeout.");
				fire();
			}

			private void fire()
			{
				if ( to == null ) {
					if ( replyToSet )
						throw new IllegalArgumentException("Trying to publish to "
							+ "event bus with replyTo. Akka does not retain the sender "
							+ "when posting to the event stream.");
					context().system().eventStream().publish(msg);
				}
				else
					to.tell(msg, replyTo);
			}


			@Override
			public void request(Class<?> response, Object requestInfo)
			{
				final Class<? extends Object> javactorClass = 
					JavactorUntypedActor.this.javactor.getClass();
				final JavactorInfo javactorInfo = JavactorUntypedActor
					.javactorInfoByJavactorType.get(javactorClass);
				if ( getCachedHandleMethodForClass(response, javactorInfo) == null ) {
					throw new IllegalStateException(javactorClass+" does not have "
						+"a Handle method for the response "+response);
				}
				if ( getCachedHandleMethodForClass(TimeoutMsg.class, javactorInfo) == null )
					throw new IllegalStateException(javactorClass+" does not have "
						+"a Handle method for "+TimeoutMsg.class);
				fire();
				startWaitingFor(response, Objects.firstNonNull(timeout, 
					Duration.create(DEFAULT_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)), 
					requestInfo);
			}

			@Override
			public SendBuilder replyToSender()
			{
				this.to = (ActorRef) AkkaJavactorContext.this.sender();
				return this;
			}
		}
		@RequiredArgsConstructor
		private class MyScheduleBuilder implements ScheduleBuilder {
			private final Object msg;
			private Object to;
			private Object from = AkkaJavactorContext.this.self();
			private FiniteDuration delay = Duration.Zero();
			private FiniteDuration period;
			@Override
			public ScheduleBuilder to(Object to)
			{
				this.to = to;
				return this;
			}

			@Override
			public ScheduleBuilder toSelf()
			{
				this.to = AkkaJavactorContext.this.self();
				return this;
			}

			@Override
			public ScheduleBuilder delay(long delay, TimeUnit timeUnit)
			{
				this.delay = Duration.create(delay, timeUnit);
				return this;
			}

			@Override
			public ScheduleBuilder period(long period, TimeUnit timeUnit)
			{
				this.period = Duration.create(period, timeUnit);
				return this;
			}

			@Override
			public Cancellable go()
			{
				final Object msgToUse = to == null ? new PostMsg(msg) : msg;
				if ( period == null ) {
					return new MyCancellable(context().system().scheduler().scheduleOnce(delay, 
						(ActorRef)to, msgToUse, context().dispatcher(), (ActorRef)from));
				}
				
				return new MyCancellable(context().system().scheduler().schedule(
					delay, period, 
					(ActorRef)to, msgToUse, context().dispatcher(), (ActorRef)from));
			}

			@Override
			public ScheduleBuilder from(Object from)
			{
				this.from = from;
				return this;
			}
			
		}
		private final class MyCancellable implements Cancellable
		{
			private final akka.actor.Cancellable cancellable;

			private MyCancellable(akka.actor.Cancellable cancellable)
			{
				this.cancellable = cancellable;
			}

			@Override
			public boolean cancel()
			{
				return cancellable.cancel();
			}
		}

		
//		@Override
//		public Cancellable schedule(FiniteDuration delay, FiniteDuration period,
//			Object to, Object msg, Object from)
//		{
//			return new MyCancellable(context().system().scheduler().schedule(delay, period, 
//				(ActorRef)to, msg, context().dispatcher(), (ActorRef)from));
//		}

		@Override
		public Object self()
		{
			return getSelf();
		}

//		@Override
//		public Cancellable scheduleOnce(FiniteDuration delay, Object to,
//			Object msg, Object from)
//		{
//			return new MyCancellable(context().system().scheduler().scheduleOnce(delay, 
//				(ActorRef)to, msg, context().dispatcher(), (ActorRef)from));
//		}

		@Override
		public void unhandled(Object msg)
		{
			unhandled(msg);
		}

		@Override
		public Object sender()
		{
			return getSender();
		}

		@Override
		public void stop(Object actor)
		{
			context().stop((ActorRef) actor);
		}

		@Override
		public SendBuilder msg(Object msg)
		{
			return new MySendBuilder(msg);
		}

		@Override
		public void watch(Object actor)
		{
			context().watch((ActorRef) actor);
		}

		@Override
		public <T> ActorBuilder<T> actorBuilder(Class<T> javactorClass,
			String actorName)
		{
			return new MyActorBuilder<T>(javactorClass, actorName);
		}

		@Override
		public ScheduleBuilder schedule(Object msg)
		{
			return new MyScheduleBuilder(msg);
		}
	}

	@Data
	static private class JavactorInfo {
		private final Field javactorContextField;
		private final ImmutableSortedMap<Class<?>,Method> methodsByMessageClass;
		private final MySupervisorStrategyInfo supervisorStrategyInfo;
	}
	static private ConcurrentHashMap<Class<?>, JavactorInfo> 
		javactorInfoByJavactorType = new ConcurrentHashMap<>();
	
	@Getter//getter for testing
	private final Object javactor;
	private final JavactorFactory javactorFactory;
	private final boolean subscribeToEventStream;
	private final Map<Class<?>, akka.actor.Cancellable> requests = 
		Maps.newHashMapWithExpectedSize(0);/* Keep it small initially */

//	private void setupSupervisorStrategy()
//	{
//		if ( supervisorStrategyInfo != null )
//			supervisorStrategy = supervisorStrategyInfo.getType().equals(SupervisorStrategyType.ONE_FOR_ONE) ?
//				new OneForOneStrategy(supervisorStrategyInfo.getMaxNumRetries(), 
//					rangeTimeUnitToDuration(supervisorStrategyInfo.getTimeRange(),
//						supervisorStrategyInfo.getTimeUnit()),
//						myDecider(),
//						supervisorStrategyInfo.isLoggingEnabled() 
//						) :
//					new AllForOneStrategy(supervisorStrategyInfo.getMaxNumRetries(), 
//						rangeTimeUnitToDuration(supervisorStrategyInfo.getTimeRange(),
//							supervisorStrategyInfo.getTimeUnit()),
//							myDecider(),
//							supervisorStrategyInfo.isLoggingEnabled());	
//	}
	
	private Function<Throwable, Directive> myDecider()
	{
		return new Function<Throwable, Directive>()
		{
			@Override
			public Directive apply(Throwable t)
			{
				if ( t instanceof ActorInitializationException
					 || t instanceof ActorKilledException
					 || t instanceof DeathPactException ) 
				{
					return SupervisorStrategy.stop();
				} 
				else if ( t instanceof Exception ) 
				{
					Class<? extends Throwable> clazz = t.getClass();
					ImmutableSet<Entry<Class<?>, Method>> entrySet = javactorInfoByJavactorType
						.get(javactor.getClass()).getSupervisorStrategyInfo().getOnExceptionMethods()
						.entrySet();
					for (Entry<Class<?>, Method> entry : entrySet)
					{
						if (entry.getKey().isAssignableFrom(clazz))
						{
							final Method method = entry.getValue();
							try
							{
								return map((SupervisorDirective) methodInvoke(
									method, javactor, t));
							} catch (Exception e)
							{
								throw new RuntimeException(e);
							}
						}
					}
					return SupervisorStrategy.restart();
				} else {
					return SupervisorStrategy.escalate();
				}
			}
		};
	}

	private Directive map(SupervisorDirective supDirective)
	{
		return JAVACTOR_DIRECTIVES_TO_AKKA.get(supDirective);
	}
	
	@Override
	public void preStart() throws Exception
	{
		super.preStart();
		if ( !javactorInfoByJavactorType.contains(javactor.getClass()) ) {
			javactorInfoByJavactorType.put(javactor.getClass(), 
				new JavactorInfo(getJavactorContextField(javactor.getClass()), 
					getHandleMethodsByMsgClass(),
					getMySupervisorStrategyInfo(javactor)));
		}
		AkkaJavactorContext javactorContext = createJavactorContext();
		setContextOnJavactor(javactorContext);
		ImmutableMap<Class<?>, Method> handleMethods = 
			javactorInfoByJavactorType.get(javactor.getClass()).getMethodsByMessageClass();
		if ( subscribeToEventStream ) {
			ImmutableSet<Class<?>> keySet = handleMethods.keySet();
			for (Class<?> msgClass : keySet)
			{
				context().system().eventStream().subscribe(getSelf(), 
					msgClass);
			}
		}
		callAnnotatedMethod(PreStart.class);
	}

	private MySupervisorStrategyInfo getMySupervisorStrategyInfo(Object javactor)
	{
		return new MySupervisorStrategyInfo(getSupervisorStrategyInfo(javactor),
			getOnExceptionMethods(javactor.getClass()));
	}

	private SupervisorStrategyInfo getSupervisorStrategyInfo(Object javactor)
	{
		try
		{
			Method[] methods = javactor.getClass().getMethods();
			for (Method method : methods)
			{
				if ( SupervisorStrategyInfo.class.isAssignableFrom(method.getReturnType()) )
					return (SupervisorStrategyInfo) method.invoke(javactor);
			}
			return new SupervisorStrategyInfo();
		} catch (Throwable e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private ImmutableMap<Class<?>, Method> getOnExceptionMethods(
		Class<?> javactorClass)
	{
		Builder<Class<?>, Method> result = ImmutableMap.builder();
		ImmutableSet<Method> methods = getMethodsWithAnnotation(javactorClass, OnException.class);
		for (Method method : methods)
		{
			result.put(method.getParameterTypes()[0], method);
		}
		return result.build();
	}

	private ImmutableSet<Method> getMethodsWithAnnotation(
		Class<?> clazz, Class<OnException> annotClass)
	{
		com.google.common.collect.ImmutableSet.Builder<Method> result = ImmutableSet.builder();
		Method[] methods = clazz.getMethods();
		for (Method method : methods)
		{
			Object annot = method.getAnnotation(annotClass);
			if ( annot != null  ) {
				result.add(method);
			}
		}
		return result.build();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void callAnnotatedMethod(Class annotClass) throws Exception
	{
		Method[] methods = javactor.getClass().getMethods();
		for (Method method : methods)
		{
			Object prestartAnnot = method.getAnnotation(annotClass);
			if ( prestartAnnot != null ) {
				methodInvoke(method, javactor);
				return;
			}
		}
	}

	private ImmutableSortedMap<Class<?>, Method> getHandleMethodsByMsgClass()
	{
		Method[] methods = javactor.getClass().getMethods();
		ImmutableSortedMap.Builder<Class<?>,Method> builder = 
			ImmutableSortedMap.orderedBy(new MostToLeastSpecificComparator());
		for (Method method : methods)
		{
			Handle handleAnnot = method.getAnnotation(Handle.class);
			if ( handleAnnot != null ) {
				Class<?>[] parameterTypes = method.getParameterTypes();
				if ( parameterTypes.length <= 0 )
					throw new RuntimeException("@Handle method "+method+
						" does not have a parameter.");
				final Class<?> msgClass = parameterTypes[0];
				builder.put(msgClass, method);
			};
		}
		return builder.build();
	}

	private Field getJavactorContextField(Class<?> clazz)
	{
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields)
		{
			if ( JavactorContext.class.isAssignableFrom(field.getType()) ) {
				field.setAccessible(true);
				return field;
			}
		}
		Class<?> superclass = clazz.getSuperclass();
		return superclass == null ? null : getJavactorContextField(superclass);
	}


	@Override
	public void onReceive(Object message) throws Exception
	{
		doneWaitingFor(message.getClass());
		
		if ( message instanceof PostMsg ) {
			context().system().eventStream().publish(((PostMsg) message).getPayload());
			return;
		}
		
		if ( message instanceof Terminated )
		{
			message = new javactor.msg.Terminated();
		}
		
		AkkaJavactorContext context = createJavactorContext();
		setContextOnJavactor(context);
		final Class<? extends Object> msgClass = message.getClass();
		final Class<? extends Object> javactorClass = javactor.getClass();
		Method method = getCachedHandleMethodForClass(msgClass, javactorInfoByJavactorType.get(javactorClass));
		if ( method != null ) {
			methodInvoke(method, javactor, message);
			return;
		}
		unhandled(message);
	}

	private Method getCachedHandleMethodForClass(
		final Class<? extends Object> msgClass, JavactorInfo javactorInfo)
	{
		final ImmutableMap<Class<?>, Method> methodsByMessageClass = javactorInfo.getMethodsByMessageClass();
		Method method = null;
		if ( methodsByMessageClass.containsKey(msgClass) ) 
		{
			method = methodsByMessageClass.get(msgClass);
		} else {
			ImmutableSet<Entry<Class<?>, Method>> entrySet = methodsByMessageClass.entrySet();
			for (Entry<Class<?>, Method> entry : entrySet)
			{
				if ( entry.getKey().isAssignableFrom(msgClass) ) {
					method = entry.getValue();
					break;
				}
			}
		}
		return method;
	}

	private void setContextOnJavactor(AkkaJavactorContext context)
	{
		try
		{
			Field javactorContextField = javactorInfoByJavactorType.get(javactor.getClass())
				.getJavactorContextField();
			if ( javactorContextField != null )
				javactorContextField.set(javactor, context);
		} catch (IllegalArgumentException | IllegalAccessException e)
		{
			throw new RuntimeException(e);
		}
	}

	private AkkaJavactorContext createJavactorContext()
	{
		return new AkkaJavactorContext();
	}

	@Override
	public void postStop() throws Exception
	{
		super.postStop();
		setContextOnJavactor(createJavactorContext());
		Method[] methods = javactor.getClass().getMethods();
		for (Method method : methods)
		{
			PostStop annot = method.getAnnotation(PostStop.class);
			if ( annot != null ) {
				methodInvoke(method, javactor);
			}	
		}
	}

	private Object methodInvoke(Method method, Object target, Object... args) 
		throws Exception
	{
		try
		{
			return method.invoke(target, args);
		} catch (InvocationTargetException e)
		{
			throw (Exception)e.getCause();
		}
	}

	@Override
	public SupervisorStrategy supervisorStrategy()
	{
		SupervisorStrategyInfo info = javactorInfoByJavactorType
			.get(javactor.getClass()).getSupervisorStrategyInfo().getInfo();
		Duration withinDuration = toDuration(info.getTimeRange(), info.getTimeUnit());
		final int maxNumRetries = info.getMaxNumRetries();
		final boolean loggingEnabled = info.isLoggingEnabled();
		return info.getType().equals(SupervisorStrategyType.ONE_FOR_ONE) ?
			new OneForOneStrategy(maxNumRetries, 
				withinDuration,
				myDecider(),
				loggingEnabled 
				) :
			new AllForOneStrategy(maxNumRetries, 
				withinDuration,
				myDecider(),
				loggingEnabled 
				);
	}

	private Duration toDuration(long timeRange, TimeUnit timeUnit)
	{
		return Duration.create(timeRange, timeUnit);
	}

	@Override
	public void preRestart(Throwable reason, Option<Object> message)
		throws Exception
	{
		log.severe("restarting for reason: "+reason);
		setContextOnJavactor(createJavactorContext());
		callAnnotatedMethod(PreRestart.class);
		super.preRestart(reason, message);
	}

	@Override
	public void postRestart(Throwable reason) throws Exception
	{
		setContextOnJavactor(createJavactorContext());
		callAnnotatedMethod(PostRestart.class);
		super.postRestart(reason);
	}
	
	public void startWaitingFor(Class<?> response, FiniteDuration timeout, 
		Object taskInfo)
	{
		requests.put(response, context().system().scheduler().scheduleOnce(timeout,
			self(), new TimeoutMsg(taskInfo), context().dispatcher(), self()));
	}

	private void doneWaitingFor(Class<? extends Object> class1)
	{
		final akka.actor.Cancellable cancellable = requests.remove(class1);
		if ( cancellable != null )
			cancellable.cancel();
	}
}
