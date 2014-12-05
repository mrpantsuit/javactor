package javactor;

import java.util.concurrent.TimeUnit;

import lombok.Data;

public interface JavactorContext
{
	public enum SupervisorStrategyType {
		ONE_FOR_ONE,
		ALL_FOR_ONE
	}
	public enum SupervisorDirective {
		RESUME,
		RESTART,
		STOP,
		ESCALATE
	}
	/**
	 * Implement in your Javactor a method that returns this type, and it will
	 * be used to configure the supervisor strategy
	 */
	@Data
	static public class SupervisorStrategyInfo {
		static public class Builder {
			private SupervisorStrategyType type;
			private Integer maxNumRetries;
			private Long timeRange;
			private TimeUnit timeUnit;
			private Boolean loggingEnabled;
			public Builder type(SupervisorStrategyType type) {
				this.type = type;
				return this;
			}
			public Builder retries(Integer maxNumRetries,
				Long timeRange, TimeUnit timeUnit) {
				this.maxNumRetries = maxNumRetries;
				this.timeRange = timeRange;
				this.timeUnit = timeUnit;
				return this;
			}
			public Builder loggingEnabled(Boolean loggingEnabled) {
				this.loggingEnabled = loggingEnabled;
				return this;
			}
			public SupervisorStrategyInfo build() {
				final SupervisorStrategyInfo result = new SupervisorStrategyInfo();
				if ( type != null ) result.setType(type);
				if ( maxNumRetries != null ) {
					result.setMaxNumRetries(maxNumRetries);
					result.setTimeRange(timeRange);
					result.setTimeUnit(timeUnit);
				}
				if ( loggingEnabled != null ) result.setLoggingEnabled(loggingEnabled);
				return result;
			}
		}
		private SupervisorStrategyType type = SupervisorStrategyType.ONE_FOR_ONE;
		private int maxNumRetries = -1;
		private long timeRange = 291 * 365;//Arbitrary long time
		private TimeUnit timeUnit = TimeUnit.DAYS;
		private boolean loggingEnabled = true;
		static public Builder builder() { return new Builder(); }
	}
	public interface SendBuilder {
		/**
		 * If none is specified, will post to event stream (event bus)
		 */
		SendBuilder to(Object to);
		SendBuilder replyToSender();
		SendBuilder from(Object from);
		SendBuilder timeout(long duration, TimeUnit timeUnit);
		void fireAndForget();
		void request(Class<?> response, Object requestInfo);
	}
	
	public interface JavactorPreparer<T> {
		void prepare(T javactor);
	}
	
	public interface ActorBuilder<T> {
		ActorBuilder<T> subscribeToEventBus();
		/**
		 * Use to override default factory
		 */
		ActorBuilder<T> factory(JavactorFactory factory);
		/**
		 * Called every time actor is created to prepare it's state. Useful for
		 * settings initial values on the actor.
		 */
		ActorBuilder<T> preparer(JavactorPreparer<T> preparer);
		ActorBuilder<T> dispatcher(String dispatcherName);
		Object build();
	}
	
	public interface ScheduleBuilder {
		/**
		 * If none is specified, will post to event stream (event bus)
		 */
		ScheduleBuilder to(Object to);
		ScheduleBuilder toSelf();
		/**
		 * Default is self
		 */
		ScheduleBuilder from(Object from);
		ScheduleBuilder delay(long delay, TimeUnit timeUnit);
		ScheduleBuilder period(long period, TimeUnit timeUnit);
		Cancellable go();
	}

	public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 
		2 * 1000 * 60;//2 min
	
	<T> ActorBuilder<T> actorBuilder(Class<T> javactorClass, String actorName);

	ScheduleBuilder schedule(Object msg);
	
	Object self();
	Object sender();

	void unhandled(Object msg);
	void stop(Object object);
	/**
	 * Use to send messages
	 */
	SendBuilder msg(Object msg);
	void watch(Object actor);

}
