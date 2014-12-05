javactor
========

Java actor API, with implementation for Akka.

This began as a library to make using Java Akka easier. Though it's evolution, it became clear that the clients
of the API didn't need to have a dependency on Akka at all; their need couls be satisfied with an actor API general
actor system provider. This is with the rather considerable caveat that many of the features Javactor exposes are
provided by and modeled after Akka. In theory, though, any actor system providing the same features could supplant Akka
in an application, without the need to rewrite any of the actors.

The principal value of Javactor at this time, though, is to make Java Akka easier.  Here's how:
* Handle messages simply by implementing a method annotated with @Handle
* Handle exceptions simply by implementing a method annotated with @OnException
* Actor builder API
* Send message builder, e.g., ctx.msg(new MyMessage()).to(someOtherActor).sender(replyTo).fireAndForget()
* Safety for message sends that should receive a response, e.g., ctx.msg(xxx).to(someActor).request(ReplyMsg.class).

More info to come.
