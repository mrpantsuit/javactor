package javactor.akka;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import javactor.Cancellable;
import javactor.JavactorContext;
import javactor.JavactorFactory;
import javactor.JavactorContext.SupervisorDirective;
import javactor.akka.JavactorUntypedActor;
import javactor.annot.Handle;
import javactor.annot.OnException;
import javactor.annot.PreRestart;
import javactor.annot.PreStart;
import javactor.msg.AReplyMsg;
import javactor.msg.Failed;
import javactor.msg.Restarted;
import javactor.msg.Success;
import javactor.msg.TestMsg;
import javactor.msg.TestMsg2;
import javactor.msg.TestMsgSubtype;
import javactor.msg.TestMsgSubtypeSubtype;
import javactor.msg.TimeoutMsg;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.junit.Before;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.AllForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.Terminated;
import akka.japi.Creator;
import akka.pattern.Patterns;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;

import com.google.common.collect.Lists;

@SuppressWarnings("serial")
public class JavactorUntypedActorTest
{
	static private class HandleThrowableTestjavactor extends SupervisorJavactor {
		@OnException
		public SupervisorDirective on(Throwable t) {
			return directive;
		}
	}
	static private class HandleSubtypeDeclaredSecondJavactor {
		JavactorContext ctx;
		@Handle
		public void handle(TestMsg msg) {
			System.out.println("Shouldn't get this!");
		}
		@Handle
		public void handle(TestMsgSubtype msg) {
			ctx.msg(msg).replyToSender().fireAndForget();
		}
	}
	static private class HandleSubtypeDeclaredFirstJavactor {
		JavactorContext ctx;
		@Handle
		public void handle(TestMsgSubtype msg) {
			ctx.msg(msg).replyToSender().fireAndForget();
		}
		@Handle
		public void handle(TestMsg msg) {
			System.out.println("Shouldn't get this!");
		}
	}
	static private class RequestWithNoCorrespondingHandleJavactor {
		Object replyTo;
		JavactorContext ctx;
		@Handle
		public void handle(TestMsg msg) {
			replyTo = ctx.sender();
			ctx.msg(msg).request(AReplyMsg.class, "making request");
		}
		@PreRestart
		public void preRestart() {
			ctx.msg(new Restarted()).to(replyTo).fireAndForget();
		}
	}
	static private class RequestWithCorrespondingHandleJavactor {
		JavactorContext ctx;
		@Handle
		public void handle(TestMsg msg) {
			ctx.msg(new Success()).to(ctx.sender()).request(AReplyMsg.class, "making request");
		}
		@Handle
		public void handle(AReplyMsg msg) {
		}
		@Handle
		public void handle(TimeoutMsg msg) {
		}
	}
	@Data
	static private class ScheduleTestData {
//		private final long startTime;
		private long expectedFirstRun;
		private long expectedSecondRun;
	}
	static abstract private class SchedulingJavactor {
		JavactorContext ctx;
		Object child;
		@PreStart
		public void pre() {
			child = ctx.actorBuilder(SchedulingChildActor.class, "child").build();
		}
	}
	static private class SchedulingJavactor1 extends SchedulingJavactor {
		@Handle
		public void handle(TestMsg msg) {
			final ScheduleTestData testData = new ScheduleTestData();
			testData.setExpectedFirstRun(System.currentTimeMillis());
			testData.setExpectedSecondRun(System.currentTimeMillis()+100);
			msg.setTestData(testData);
			ctx.schedule(msg).to(child).period(100, TimeUnit.MILLISECONDS)
				.from(ctx.sender()).go();
		}
	}
	static private class SchedulingJavactor2 extends SchedulingJavactor {
		@Handle
		public void handle(TestMsg msg) {
			final ScheduleTestData testData = new ScheduleTestData();
			testData.setExpectedFirstRun(System.currentTimeMillis()+100);
			testData.setExpectedSecondRun(System.currentTimeMillis()+300);
			msg.setTestData(testData);
			ctx.schedule(msg).to(child)
				.delay(100, TimeUnit.MILLISECONDS)
				.period(200, TimeUnit.MILLISECONDS)
				.from(ctx.sender()).go();
		}
	}
	static private class ScheduleOnceJavactor extends SchedulingJavactor {
		@Handle
		public void handle(TestMsg msg) {
			final ScheduleTestData testData = new ScheduleTestData();
			testData.setExpectedFirstRun(System.currentTimeMillis());
			msg.setTestData(testData);
			ctx.schedule(msg).to(child).from(ctx.sender()).go();
		}
	}
	static private class CancelScheduleJavactor extends SchedulingJavactor {
		private Cancellable cancellable;

		@Handle
		public void handle(TestMsg msg) {
			final ScheduleTestData testData = new ScheduleTestData();
			testData.setExpectedFirstRun(System.currentTimeMillis());
			msg.setTestData(testData);
			cancellable = ctx.schedule(msg)
				.period(100, TimeUnit.MILLISECONDS)
				.to(child).from(ctx.sender()).go();
		}

		@Handle
		public void handle(TestMsg2 msg) {
			cancellable.cancel();
		}
	}
	static public class SchedulingChildActor
	{
		JavactorContext ctx;
		int run = 0;
		@Handle
		public void handle(TestMsg msg) {
			ScheduleTestData testData = (ScheduleTestData) msg.getTestData();
			if ( ( run == 0 && Math.abs(System.currentTimeMillis() - testData.getExpectedFirstRun()) > 25)
				 || ( run == 1 && Math.abs(System.currentTimeMillis() - testData.getExpectedSecondRun()) > 25) )
			{
				ctx.msg(Failed.SHARED).replyToSender().fireAndForget();
				ctx.stop(ctx.self());
			} else {
				ctx.msg(msg).replyToSender().fireAndForget();
			}
			run++;
		}
	}
	
	@RequiredArgsConstructor
	static private final class MyCreator implements Creator<JavactorUntypedActor>
	{
		private final Object javactor;
		@Override
		public JavactorUntypedActor create() throws Exception
		{
			return new JavactorUntypedActor(javactor, new JavactorFactory()
			{
				@Override
				public Object get(Class<?> aClass)
				{
					try
					{
						return aClass.getConstructors()[0].newInstance();
					} catch (Throwable e)
					{
						throw new RuntimeException(e);
					}
				}
			}, false);
		}
	}

	private Object javactor;
	private ActorSystem system;
	
	@Before
	public void setUp() throws Exception
	{
		system = ActorSystem.create();
	}

	@Test
	public void test_sup_strategy_default() throws Throwable
	{
		javactor = new NoOnExceptionMethodsTestJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(BoomActor.class, probe.getRef());
				final ActorRef child = probe.expectMsgClass(ActorRef.class);
				probe.watch(child);
				target.tell(new TestMsg(), probe.getRef());
				probe.expectMsgClass(Restarted.class);
			}
		};			
	}
	
	@Test
	public void test_sup_strategy_stop() throws Throwable
	{
		javactor = new HandleRuntimeExceptionTestJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				Duration timeout = Duration.create("5 second");
				final ActorRef child = (ActorRef) Await.result(
					Patterns.ask(target, BoomActor.class, 5000),
					timeout);
				probe.watch(child);
				target.tell(SupervisorDirective.STOP, probe.getRef());
				probe.expectMsgClass(Object.class);
				target.tell(new TestMsg(), null);
				final Terminated msg = probe.expectMsgClass(Terminated.class);
				assertEquals(msg.getActor(), child);
			}
		};
	}

	@Test
	public void test_sup_strategy_all_for_one_type() throws Throwable
	{
		javactor = new AllForOneTestJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final TestActorRef<JavactorUntypedActor> ref = TestActorRef.create(system, props,
			"testA");
		final SupervisorStrategy supervisorStrategy = ref.underlyingActor().supervisorStrategy();
		assertTrue(supervisorStrategy instanceof AllForOneStrategy);
		AllForOneStrategy afo = (AllForOneStrategy)supervisorStrategy;
		assertEquals(10, afo.maxNrOfRetries());
		assertEquals(Duration.create("1 minute"), afo.withinTimeRange());
	}
	
	@Test
	public void test_sup_strategy_on_exception_method_param_supertype_of_thrown_exception() throws Throwable
	{
		javactor = new HandleThrowableTestjavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				Duration timeout = Duration.create("5 second");
				final ActorRef child = (ActorRef) Await.result(
					Patterns.ask(target, BoomActor.class, 5000),
					timeout);
				probe.watch(child);
				target.tell(SupervisorDirective.STOP, probe.getRef());
				probe.expectMsgClass(Object.class);
				target.tell(new TestMsg(), null);
				final Terminated msg = probe.expectMsgClass(Terminated.class);
				assertEquals(msg.getActor(), child);
			}
		};
	}	

	@Test
	public void test_selects_handle_method_with_most_specific_type__subtype_declared_second() throws Throwable
	{
		javactor = new HandleSubtypeDeclaredSecondJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsgSubtype(), probe.getRef());
				probe.expectMsgClass(TestMsgSubtype.class);
			}
		};
	}	

	@Test
	public void test_selects_handle_method_with_most_specific_type__subtype_declared_first() throws Throwable
	{
		javactor = new HandleSubtypeDeclaredFirstJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsgSubtype(), probe.getRef());
				probe.expectMsgClass(TestMsgSubtype.class);
			}
		};
	}	

	@Test
	public void test_selects_handle_method_with_most_specific_type__subtype_declared_second__match_supertype_of_msg() throws Throwable
	{
		javactor = new HandleSubtypeDeclaredSecondJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsgSubtypeSubtype(), probe.getRef());
				probe.expectMsgClass(TestMsgSubtype.class);
			}
		};
	}	

	@Test
	public void test_selects_handle_method_with_most_specific_type__subtype_declared_first__match_supertype_of_msg() throws Throwable
	{
		javactor = new HandleSubtypeDeclaredFirstJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsgSubtypeSubtype(), probe.getRef());
				probe.expectMsgClass(TestMsgSubtype.class);
			}
		};
	}	
	
	@Test
	public void test_request_without_corresponding_handle_method() throws Exception
	{
		javactor = new RequestWithNoCorrespondingHandleJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				probe.expectMsgClass(Restarted.class);
			}
		};
	}
	
	@Test
	public void test_request_with_corresponding_handle_method() throws Exception
	{
		javactor = new RequestWithCorrespondingHandleJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final ActorRef target = system.actorOf(props);
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				probe.expectMsgClass(Success.class);
			}
		};
	}

	@Test
	public void test_scheduling() throws Exception
	{
		javactor = new SchedulingJavactor1();
		final Props props = Props.create(new MyCreator(javactor));
		final TestActorRef<JavactorUntypedActor> target = TestActorRef.create(system, props,
			"test");
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				Object[] receiveN = probe.receiveN(3, Duration.create(1, TimeUnit.SECONDS));
				final ArrayList<Object> list = Lists.newArrayList(receiveN);
				assertFalse("failed in list "+list, list.contains(Failed.SHARED));
			}
		};
	}

	@Test
	public void test_scheduling_2() throws Exception
	{
		javactor = new SchedulingJavactor2();
		final Props props = Props.create(new MyCreator(javactor));
		final TestActorRef<JavactorUntypedActor> target = TestActorRef.create(system, props,
			"test");
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				Object[] receiveN = probe.receiveN(3, Duration.create(1, TimeUnit.SECONDS));
				final ArrayList<Object> list = Lists.newArrayList(receiveN);
				assertFalse("failed in list "+list, list.contains(Failed.SHARED));
			}
		};
	}

	@Test
	public void test_schedule_once() throws Exception
	{
		javactor = new ScheduleOnceJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final TestActorRef<JavactorUntypedActor> target = TestActorRef.create(system, props,
			"test");
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				Object[] receiveN = probe.receiveN(1, Duration.create(1, TimeUnit.SECONDS));
				final ArrayList<Object> list = Lists.newArrayList(receiveN);
				assertFalse("failed in list "+list, list.contains(Failed.SHARED));
			}
		};
	}
	@Test
	public void test_schedule_cancel() throws Exception
	{
		javactor = new CancelScheduleJavactor();
		final Props props = Props.create(new MyCreator(javactor));
		final TestActorRef<JavactorUntypedActor> target = TestActorRef.create(system, props,
			"test");
		
		new JavaTestKit(system)
		{
			{
				final JavaTestKit probe = new JavaTestKit(system);
				target.tell(new TestMsg(), probe.getRef());
				target.tell(new TestMsg2(), probe.getRef());
				Object[] receiveN = probe.receiveN(1, Duration.create(1, TimeUnit.SECONDS));
				final ArrayList<Object> list = Lists.newArrayList(receiveN);
				assertFalse("failed in list "+list, list.contains(Failed.SHARED));
			}
		};
	}
}
