FROM extender-base

ENV MANIFEST_MERGE_TOOL /opt/local/bin/manifestmergetool.jar

COPY users/ users/
ADD extender-0.1.0.jar app.jar
ADD manifestmergetool-0.1.0.jar $MANIFEST_MERGE_TOOL
RUN chown extender: app.jar

# Extender data cache
RUN mkdir -p /var/extender/cache/data && \
    chown -R extender: /var/extender/cache

USER extender
ENTRYPOINT ["java","-Xmx2g","-XX:MaxDirectMemorySize=1g","-jar","/app.jar"]
EXPOSE 9000
