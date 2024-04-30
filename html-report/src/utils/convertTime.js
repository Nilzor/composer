module.exports = (time) => {
  let ms = time % 1000;
  time = (time - ms) / 1000;
  const secs = time % 60;
  time = (time - secs) / 60;
  const mins = time % 60;
  //const hours = Math.floor(time / 1000 / 60 / 60)
  time = (time - mins) / 60;
  const hours = time;

  const msLenth = ms.toString().length;
  if (msLenth === 2 ) ms = '0'+ms;
  if (msLenth === 1 ) ms = '00'+ms;

  if (hours > 0) {
    return hours + ":" + mins + ':' + secs + "." + ms;
  } else {
    return mins + ':' + secs + "." + ms;
  }
// 4 992 000
};
