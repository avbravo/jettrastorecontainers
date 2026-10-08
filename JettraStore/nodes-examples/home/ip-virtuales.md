# Markdown Cheat Sheet (Example File)
Sí, puedes usar una interfaz de red virtual que sea completamente local y aislada (loopback virtual), de modo que los paquetes de red nunca salgan a tu red física ni a internet, simulando nodos separados dentro de la misma máquina.

Para lograrlo, la opción más limpia en Linux es utilizar la interfaz lo (loopback) existente añadiendo sub-direcciones dentro del bloque reservado 127.0.0.0/8.
Pasos para configurarlo:


sudo ip addr add 127.0.0.2/8 dev lo
sudo ip addr add 127.0.0.3/8 dev lo
sudo ip addr add 127.0.0.4/8 dev lo

Cómo verificar: Ejecuta 
ip addr show lo

 y comprueba que aparezcan ambas direcciones IP asociadas a la interfaz lo.


Configurar JettraStore con las IPs locales

Ahora arranca tus nodos apuntando a estas IPs puramente locales:

Nodo Principal (127.0.0.2):

java --enable-preview --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Djava.rmi.server.hostname=127.0.0.2 \
     -Djettra.node.id=node-01 -Djettra.node.role=PRIMARY \
     -Djettra.config.path=jettra.config -Ddatabase.properties.path=database.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar


Nodo Secundario (127.0.0.3):

java --enable-preview --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -XX:+UseZGC \
     -Xms2g -Xmx6g \
     -Djava.rmi.server.hostname=127.0.0.3 \
     -Djettra.node.id=node-02 -Djettra.node.role=SECONDARY \
     -Djettra.config.path=jettra.config -Ddatabase.properties.path=database.properties \
     -jar JettraStore-1.0-SNAPSHOT-uber.jar

