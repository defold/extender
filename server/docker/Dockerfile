FROM extender-base

ADD extender-0.1.0.jar app.jar
RUN chown extender: app.jar

# Extender data cache
RUN mkdir -p /var/extender/cache/data && \
    chown -R extender: /var/extender/cache

USER extender
ENTRYPOINT ["java","-Xmx1g","-XX:MaxDirectMemorySize=512m","-jar","/app.jar"]
EXPOSE 9000
