package javactor.msg;

import lombok.Data;

@Data
public class TimeoutMsg
{
	private final Object taskInfo;
}
