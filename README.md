# fb-delta
A FindBugs report delta ant task

Add an ant task like the following

    <target name="sample_delta" xmlns:fbdelta="antlib:com.mebigfatguy.fbdelta">
		    <fbdelta:fbdelta baseReport="original_report.xml" updateReport="update_report.xml"         
		                     outputReport="output.xml" changed="delta"/>
		<antcall target="report"/>
	</target>
	
	<target name="report" if="${delta}">
		<loadfile property="diff" srcFile="output.xml"/> 
		<echo>${diff}</echo> 
	</target>
