FROM eclipse-temurin:8-jdk-focal
WORKDIR /app
COPY . .
RUN javac PDFApp/*.java PDFServer/*.java
RUN echo '#!/bin/bash \n\
tnameserv -ORBInitialPort 1050 & \n\
sleep 10 \n\
java -cp . PDFServer.PDFServer -ORBInitialPort 1050 -ORBInitialHost localhost & \n\
sleep 10 \n\
java -cp . PDFServer.PDFWebGateway' > start.sh
RUN chmod +x start.sh
CMD ["/bin/bash", "./start.sh"]
