FROM eclipse-temurin:8-jdk-focal
WORKDIR /app
COPY . .
RUN cd src && javac PDFApp/*.java PDFServer/*.java
RUN echo '#!/bin/bash \n\
cd src \n\
tnameserv -ORBInitialPort 1050 & \n\
sleep 10 \n\
java -cp . PDFServer.PDFServer -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 & \n\
sleep 10 \n\
exec java -cp . PDFServer.PDFWebGateway' > start.sh
RUN chmod +x start.sh
CMD ["/bin/bash", "./start.sh"]
