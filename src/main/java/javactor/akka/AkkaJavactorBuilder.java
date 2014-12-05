package javactor.akka;

import java.lang.reflect.InvocationTargetException;

import javactor.JavactorFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AkkaJavactorBuilder
{
	private static final class NoArgConstructorJavactorFactory implements
		JavactorFactory
	{
		@Override
		public Object get(Class<?> aClass)
		{
			try
			{
				return aClass.getConstructor().newInstance();
			} catch (InstantiationException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	private final Object target;
	private JavactorFactory javactorFactory = new NoArgConstructorJavactorFactory();
	private boolean subscribeToEventStream = true;
	static public AkkaJavactorBuilder builder(Object target) {
		return new AkkaJavactorBuilder(target);
	}
	public AkkaJavactorBuilder javactorFactory(JavactorFactory javactorFactory) {
		this.javactorFactory = javactorFactory;
		return this;
	}
	public AkkaJavactorBuilder subscribeToEventStream(boolean flag) {
		this.subscribeToEventStream = flag;
		return this;
	}
	
	public JavactorUntypedActor build() {
		return new JavactorUntypedActor(target, javactorFactory,
			subscribeToEventStream);
	}
}
