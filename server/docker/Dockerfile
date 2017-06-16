FROM extender-base

RUN \
  useradd -r -u 2222 extender && \
  mkdir -p /var/extender && \
  chown extender: /var/extender && \
  chown extender: $(readlink -f /usr/bin/java) && \
  chmod +s $(readlink -f /usr/bin/java) && \
  chown -R extender:extender ${WINEPREFIX}

USER extender

ADD extender-0.1.0.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
EXPOSE 9000
