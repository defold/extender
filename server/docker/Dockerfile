FROM extender-base

ADD extender-0.1.0.jar app.jar
RUN chown extender: app.jar

USER extender

ENTRYPOINT ["java","-Xmx500m","-jar","/app.jar"]
EXPOSE 9000
