javactor
========

Java actor API, with an implementation for Akka.

This began as a library to make using Java Akka easier. Though it's evolution, though, it became clear that clients
of the API didn't need to have a dependency on Akka at all; their needs coulds be satisfied with an actor API general
to any actor system provider. This is with the rather considerable caveat that many of the features Javactor exposes are
provided by and modeled after Akka. In theory, though, any actor system providing the same features could supplant Akka
in an application written to Javactor, without the need to rewrite any of the actors.

The principal value of Javactor at this time, though, is to make Java Akka easier.  Here's how:
* Actors are POJOs, and don't need to inherit from UntypedActor
* Handle messages simply by implementing a method annotated with @Handle
* Handle exceptions simply by implementing a method annotated with @OnException
* Annotations for all the standard actor life cycle events
* Builder DSL for sending messages, e.g., ctx.msg(new MyMessage()).to(someOtherActor).sender(replyTo).fireAndForget()
* Safety for message sends that should receive a response, e.g., ctx.msg(xxx).to(someActor).request(ReplyMsg.class). Javactor will ensure that the sender has a corresponding handle method for the reply message type. It will also ensure the sender handles a timeout message, which is sent if the reply does not arrive within a timeout.
* Builder DSL for scheduling messages, e.g., ctx.schedule(new MyMessage()).to(ctx.self()).delay(1, TimeUnit.SECONDS).period(2, TimeUnit.MINUTES).go();

Javactor uses reflection to invoke methods, so there would be some performance penalty. Also, there is some memory overhead on each actor. The degree of these effects is not yet known, as I have yet to measure them. This will not be a concern, of course, for applications using the actor pattern for purposes of concurrency correctness and resilience, rather than performance.

Usage
-----

Create javactor:

public class MyActor
{
  JavactorContext ctx;//Will be set by the Javactor implementation before each interaction with the Javactor
  
  @Handle
  public void handle(SomeMessage msg) {
    // Handle message here
  }
}

Create Akka actor based on this Javactor:

ActorSystem system = ActorSystem.create();
system.actorOf(Props.create(new MyActorCreator(new MyActor())), "myactor");

Where MyActorCreator is:
	@RequiredArgsConstructor//using project lombok for brevity
	private static final class MyActorCreator implements
		Creator<JavactorUntypedActor>
	{
		private final Object myActor;
		@Override
		public JavactorUntypedActor create() throws Exception
		{
			return AkkaJavactorBuilder.builder(myActor).build();
		}
	}

This might seem ownerous, but thereafter, all Javactors can create child actors like so:

Object javactor = ctx.actorBuilder(SomeOtherJavactor.class, "otheractor").build();

Note that the result of this method is an Object. A more specific type is unnecessary, as when using Javactors,
methods are never called on actor references.
