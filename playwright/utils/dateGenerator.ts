
export function getDateInThePast(monthsInPast: number, formatWith: string){
    const date = new Date();
    date.setMonth(date.getMonth() - monthsInPast);

    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();
    if(formatWith=="/"){
        return `${day}/${month}/${year}`;
    }
    return `${year}-${month}-${day}`
}