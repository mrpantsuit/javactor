package javactor.akka;

import java.util.concurrent.TimeUnit;

import javactor.JavactorContext.SupervisorStrategyInfo;
import javactor.JavactorContext.SupervisorStrategyType;


public class AllForOneTestJavactor extends SupervisorJavactor
{
	public SupervisorStrategyInfo supervisorStrategyInfo() {
		return SupervisorStrategyInfo.builder().type(SupervisorStrategyType.ALL_FOR_ONE)
			.retries(10, 1L, TimeUnit.MINUTES)
			.build();
	}
}
