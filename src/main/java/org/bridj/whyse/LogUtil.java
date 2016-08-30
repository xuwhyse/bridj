package org.bridj.whyse;

import java.net.URL;

import org.apache.log4j.Logger;

public class LogUtil {

	static Logger log ;
	/**
	 * @param args
	 * author:xumin 
	 * 2016-8-30 下午5:54:45
	 */
	public static void main(String[] args) {
		URL url = SLog4J.class.getResource("/log4j.properties");
		System.err.println(url.getPath());
		
		Logger log = getLog();
		log.debug("debug");
		log.info("info");
	}
	public static Logger getLog() {
		if(log!=null)
			return log;
		SLog4J.init();
		log = Logger.getLogger(LogUtil.class);
		return log;
	}

}
