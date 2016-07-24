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
	
fb-delta is available on [maven.org](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.mebigfatguy.fb-contrib%22%20AND%20a%3A%22fb-contrib%22)

       GroupId: com.mebigfatguy.fb-delta
    ArtifactId: fb-delta
       Version: 0.4.1
