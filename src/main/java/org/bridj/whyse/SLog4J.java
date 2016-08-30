package org.bridj.whyse;

import java.io.File;
import java.net.URL;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;


public class SLog4J
{
	/**
	 * init log4j，並由 -Dlog4j 讀取 log4j 的 config 檔
	 */
	public static void init()
	{
//		URL url = SLog4J.class.getResource("C:/ctpfile/log4j.properties");
//		if(url==null)
//			return;
		String path = "C:/ctpfile/log4j.properties";
		File file = new File(path);
		if(file.exists())
			PropertyConfigurator.configure(path);
//		System.err.println(url.getPath());
//		BasicConfigurator.configure();
	}
	
	public static void init(String sFile)
	{
		String sLog4jConfig = System.getProperty("log4j", sFile);
		if (sLog4jConfig != null && sLog4jConfig.length() != 0)
		{
			// Check if file exists
			//
			try
			{
				File file = new File(sLog4jConfig);
				if (file.exists())
				{
					PropertyConfigurator.configure(sLog4jConfig);
					return;
				}
			}
			catch (Exception e)
			{
			}
		}

		// use default configuration
		BasicConfigurator.configure();
	}
}
