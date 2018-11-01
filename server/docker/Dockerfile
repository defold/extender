FROM extender-base

ADD extender-0.1.0.jar app.jar
RUN chown extender: app.jar

# Extender data cache
RUN mkdir -p /var/extender/cache/data && \
    chown -R extender: /var/extender/cache

# Create a daemon script for the extender (Execute the internal command as the extender user)
RUN mkdir -p /etc/service/extender && \
	echo "#!/bin/sh" > /etc/service/extender/run && \
	echo "exec /sbin/setuser extender java -Xmx500m -jar /app.jar" >> /etc/service/extender/run && \
	chmod +x /etc/service/extender/run && \
	chown extender: /etc/service/extender/run

# Needs to run as root
CMD ["/sbin/my_init"]

EXPOSE 9000
