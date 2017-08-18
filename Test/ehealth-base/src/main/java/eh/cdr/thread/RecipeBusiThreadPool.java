package eh.cdr.thread;

import eh.bus.thread.BusEventExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 线程池管理
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2016/6/14.
 */
public class RecipeBusiThreadPool<T> extends BusEventExecutor{

    private Runnable runnable;

    private List<? extends Callable<T>> callableList;

    public RecipeBusiThreadPool(Runnable runnable){
        if(null == runnable){
            return;
        }
        this.runnable = runnable;
    }

    public RecipeBusiThreadPool(List<? extends Callable<T>> callableList){
        if(null == callableList || callableList.isEmpty()){
            return;
        }

        this.callableList = callableList;
    }

    public void execute() throws InterruptedException{
        ThreadPoolTaskExecutor service = getBusTaskExecutor();
        if(null != service){
            if(null != runnable){
                service.execute(runnable);
            }

            if(null != callableList && !callableList.isEmpty()){
                service.getThreadPoolExecutor().invokeAll(callableList);
            }
        }
    }

}
